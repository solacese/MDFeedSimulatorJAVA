package com.solace.demos.trading;

import java.text.DecimalFormat;
import java.util.Random;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Properties;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishEventHandler;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessage;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import com.solacesystems.jcsmp.XMLMessageProducer;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

public class PortfolioManager {
	
	// logging interface
	private static Logger log = null;
	
	// logging initialiser
	static
	{
		log = Logger.getLogger(PortfolioManager.class);
	}
	
	public static String SOLACE_IP_PORT = null;
	public static String SOLACE_VPN = null;	
	public static String SOLACE_CLIENT_USERNAME = null;
	public static String SOLACE_PASSWORD = null;
	public static String EXCHANGE = null;
	public static String INSTRUMENTS = null;
	
	public static Properties instrumentsList = null;
	
	public static String topicPortfolioFetch = "";
	public static String topicSettlementUpdates = "";
	
	public static DecimalFormat df_3dec = new DecimalFormat("#.###");
	public static DecimalFormat df_nodec = new DecimalFormat("###,###");
	
	public static HashMap<String, JSONObject> allKnownPortfolios = new HashMap<String, JSONObject>();
		
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		// If there is not a log4j.properties in the current working directory, use the one bundled in jar.
		checkForLog4jConfigFile();
		
		PortfolioManager mdResponder = new PortfolioManager();
		if(mdResponder.parseArgs(args) ==1 || mdResponder.validateParams() ==1) {
			log.error(mdResponder.getCommonUsage());
		}
		else {
			PortfolioResponseThread portfolioResponder = mdResponder.new PortfolioResponseThread();
			portfolioResponder.start();
		}
	}
	
	public static void checkForLog4jConfigFile() {

		File configFile = new File(System.getProperty("user.dir") + System.getProperty("file.separator") + "log4j.properties");
		if (configFile.exists()) {
			System.out.println("Using log4j.properties file found at current working directory: " + configFile.getAbsolutePath());
			PropertyConfigurator.configure(configFile.getAbsolutePath());
		}
		else
		{
			// The file will be bundled in the runnable jar. The resource is at the same level as this class file. 
			PropertyConfigurator.configure(MDFeedhandler.class.getResource("log4j.properties"));	
		}
		
	}
	
	public String getCommonUsage() {
		String str = "Common parameters:\n";
		str += "\t -h HOST[:PORT]  Router IP address [:port, omit for default]\n";
		str += "\t -v VPN          vpn name (omit for default)\n";
		str += "\t -u USER         Authentication username\n";
		str += "\t -e EXCHANGE     Name of Exchange (NSE, BSE, MSE)\n";
		str += "\t -i INSTRUMENTS  Properties file containing instruments\n";
		str += "\t[-p PASSWORD]    Authentication password\n";
		return str;
	}
	
	public int parseArgs(String[] args) {
		try {
			for (int i = 0; i < args.length; i++) {
				if (args[i].equals("-h")) {
					i++;
					SOLACE_IP_PORT = args[i];
				} else if (args[i].equals("-u")) {
					i++;
					SOLACE_CLIENT_USERNAME = args[i];
				} else if (args[i].equals("-p")) {
					i++;
					SOLACE_PASSWORD = args[i];
				} else if (args[i].equals("-e")) {
					i++;
					EXCHANGE = args[i].toUpperCase();	
				} else if (args[i].equals("-i")) {
					i++;
					INSTRUMENTS = args[i];		
				} else if (args[i].equals("-v")) {
					i++;
					SOLACE_VPN = args[i];
				} else if (args[i].equals("--help")) {
					return 1; // err: print help
				} else {
					return 1; // err: print help
				}
				
			}
		} catch (Exception e) {
			return 1; // err
		}

		return 0; // success
	}
	
	private int validateParams(){
		if (SOLACE_IP_PORT == null) return 1;
		if (SOLACE_VPN == null) SOLACE_VPN = "default";
		if (SOLACE_CLIENT_USERNAME == null) return 1;
		if (EXCHANGE == null) return 1;
		if (INSTRUMENTS == null) return 1;
		
		topicPortfolioFetch = "PORTFOLIO/" + EXCHANGE + "/FETCH";
		topicSettlementUpdates = "SETTLEMENT/" + EXCHANGE + "/>";

		return 0;
	}
	

	class PortfolioResponseThread extends Thread {
		
		JCSMPSession session = null;
	    XMLMessageProducer prod = null;  
	    XMLMessageConsumer cons = null; 
		
		public void run() {

			initInstrumentsList();
			initSolace();
			
			// Everything is up and running...
	        log.info("Ready. Press enter to exit.");
	        //System.in.read();
			try {
				sleep(Long.MAX_VALUE);
			} catch (InterruptedException e) {
				log.error(e.getMessage());
			}

		}
		
		
		public String getPortfolioMessage (String account){
			
			log.info("Fetching portfolio message for account: " + account);
			if (allKnownPortfolios.containsKey(account)) {
				log.debug("The account '" + account +"' already existed in the portfolios list, will fetch and return.");
				return allKnownPortfolios.get(account).toJSONString();
			}
			else {
				log.debug("The account '" + account +"' is new, will build new portfolio for it.");
				allKnownPortfolios.put(account, createPortfolioMessage(account));
				return allKnownPortfolios.get(account).toJSONString();
			}
			
		}
		
		@SuppressWarnings("unchecked")
		public void updatePortfolio (String account, String instrument, int qty, String price, String type){
			
			
			
			if (allKnownPortfolios.containsKey(account)) {
				log.info("Updating the portfolio for account '" + account +"', instrument: '" + instrument + "', quantity: " + qty + ", price: " + price +" and type: " + type);
				
				JSONObject jsonMessage = allKnownPortfolios.get(account);
				JSONArray instrumentsArray = (JSONArray)jsonMessage.get("instruments");
				
				// Does the instrument already exist in the portfolio?
				
				int arrLength = instrumentsArray.size();
				int existingIndex = -1;
				
				for (int i = 0; i < arrLength; i++) {
					
					String existingInstrument = (String) ((JSONObject)instrumentsArray.get(i)).get("instrument");
					if (existingInstrument.equalsIgnoreCase(instrument)) {
						existingIndex = i;
						break;
					}
				}
				
				if (existingIndex > -1) {
					JSONObject instrumentEntry = (JSONObject)instrumentsArray.get(existingIndex);
					
					if (type.equalsIgnoreCase("sell")) {
						
						// Need to reduce qty from current entry
						int currentQty = Integer.parseInt((String)instrumentEntry.get("qty"));
						int newQty = currentQty - qty;
						
						if (newQty > 0)
						{
							log.debug("New Qty after subtraction will be " + newQty + ", will update portfolio.");

							// Storage is as formatted string
							instrumentEntry.put("qty", Integer.toString(newQty));
							// Remove the current entry and then re-add new
							instrumentsArray.remove(existingIndex);
							instrumentsArray.add(instrumentEntry);
						}
						else {
							log.debug("New Qty after subtraction will be " + newQty + " so deleting instrument from portfolio.");
							// Qty is 0, no need for that instrument entry
							instrumentsArray.remove(existingIndex);
						}
						
						jsonMessage.put("instruments", instrumentsArray);
						allKnownPortfolios.put(account, jsonMessage);
					}
					else {
						// Need to add qty to current entry
						int currentQty = Integer.parseInt((String) instrumentEntry.get("qty"));
						int newQty = currentQty + qty;
						
						log.debug("New Qty after addition will be " + newQty + ", will update portfolio.");
						
						instrumentEntry.put("qty", Integer.toString(newQty));
						// Remove the current entry and then re-add new
						instrumentsArray.remove(existingIndex);
						instrumentsArray.add(instrumentEntry);
						
						jsonMessage.put("instruments", instrumentsArray);
						allKnownPortfolios.put(account, jsonMessage);
					}
					
				}
				else
				{
					log.info("Adding new instrument to portfolio.");
					log.debug("Current instruments count in portfolio: " + allKnownPortfolios.get(account).get("instruments").toString());
					// Need to add it new...
					JSONObject instrumentEntry = new JSONObject();
					
					instrumentEntry.put("instrument", instrument);
					instrumentEntry.put("inv_price", price);
					instrumentEntry.put("qty", Integer.toString(qty));
			    	
					instrumentsArray.add(instrumentEntry);
					jsonMessage.put("instruments", instrumentsArray);
					allKnownPortfolios.put(account, jsonMessage);	
					
					log.debug("New instruments count in portfolio: " + allKnownPortfolios.get(account).get("instruments").toString());

				}				
			}
			else {
				log.debug("The account '" + account +"' has no portfolio entry, will build a new one and then apply.");
				allKnownPortfolios.put(account, createPortfolioMessage(account));
				
				// call self again
				updatePortfolio(account, instrument, qty, price, type);
				
			}	
		}
		
		@SuppressWarnings("unchecked")
		public JSONObject createPortfolioMessage (String account){
			
			log.debug("====================");
			log.debug("Building new portfolio for account '" + account +"'.");
			
			Random random = new Random();
			// Firstly, how many instruments in the portfolio? Random number upto half the max instrument count.
			int portfolioCount = random.nextInt(instrumentsList.size() / 2);
			log.debug("This portfolio will be max size of " + portfolioCount);
			
			// The JSON message to create along with the nested objects
			JSONObject jsonMessage = new JSONObject();
			JSONObject instrumentEntry = new JSONObject();
			JSONArray instrumentsArray = new JSONArray();
			
			// The standard parts of the message...
			jsonMessage.put("type", "Portfolio Update");
			jsonMessage.put("account", account);
			jsonMessage.put("exchange", PortfolioManager.EXCHANGE);
			
			// Now to populate the instruments Array...
			Enumeration<String> enumInstruments = (Enumeration<String>) instrumentsList.propertyNames();
			
			int count = 0;
		    while (enumInstruments.hasMoreElements() && count < portfolioCount) 
		    {
		    	// (1) Iterate through the instruments list
		    	String instrument = enumInstruments.nextElement();
		    	
		    	// (2) Do we want this one?
			    if (random.nextBoolean()){
			    	
			    	// Yes let's add this instrument...
			    	double price = Double.parseDouble(instrumentsList.getProperty(instrument));
			    	int qty = (int) (Math.random() * 100);
			    	
			    	log.debug("Adding instrument " + instrument + " at price: " + df_3dec.format(price) + " and qty: " + df_nodec.format(qty));
			    	
			    	instrumentEntry = new JSONObject();
					instrumentEntry.put("instrument", instrument);
					instrumentEntry.put("inv_price", df_3dec.format(price));
					instrumentEntry.put("qty", Integer.toString(qty));
			    	
					instrumentsArray.add(instrumentEntry);
			    	count++;
			    }
		    }			
			jsonMessage.put("instruments", instrumentsArray);

			log.debug("====================");
			return jsonMessage;
		}
		
		
		public void publishToSolace(String topicString, String payload) {
			
			try {
				if (session!=null && session.isClosed()) {
					log.warn("Session is not ready yet, waiting 5 seconds");
					Thread.sleep(5000);
					session.connect();
					publishToSolace (topicString, payload);
				}
				else if (session == null) {
					initSolace();
					publishToSolace (topicString, payload);
				}
				else { 
					
					Topic topic = JCSMPFactory.onlyInstance().createTopic(topicString);
	
					XMLMessage msg = prod.createBytesXMLMessage();
					msg.writeAttachment(payload.getBytes());
					msg.setDeliveryMode(DeliveryMode.DIRECT);
					prod.send(msg, topic);
					Thread.sleep(100);

					//log.error("Sent message:"+msg.dump());
				}
			} catch (Exception ex) {
				// Normally, we would differentiate the handling of various exceptions, but
				// to keep this sample as simple as possible, we will handle all exceptions
				// in the same way.
				log.error("Encountered an Exception: " + ex.getMessage());
				log.error(ex.getStackTrace());
				finish(1);
			}
		}

		public void initInstrumentsList() {
			
			if (instrumentsList != null) return;

			log.info("About to initialise instruments list from file: " + INSTRUMENTS);
			
			try (InputStream input = new FileInputStream(INSTRUMENTS)) {

				// This version will be updated with new prices
	            instrumentsList = new Properties();
	            instrumentsList.load(input);

	            log.info("Loaded " + instrumentsList.size() + " instruments from file.");
	            
	        } catch (IOException ex) {
	        	log.error("Encountered an Exception: " + ex.getMessage());
				log.error(ex.getStackTrace());
				finish(1);
	        }
		}
		
		public void initSolace() {
			
			if (session!=null && !session.isClosed()) return;

			try {
				log.info("About to create session.");
				
				JCSMPProperties properties = new JCSMPProperties();

				properties.setProperty(JCSMPProperties.HOST, SOLACE_IP_PORT);
				properties.setProperty(JCSMPProperties.USERNAME, SOLACE_CLIENT_USERNAME);
				properties.setProperty(JCSMPProperties.VPN_NAME, SOLACE_VPN);
				properties.setProperty(JCSMPProperties.PASSWORD, SOLACE_PASSWORD);
				properties.setBooleanProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);
				
			       // Channel properties
		        JCSMPChannelProperties chProperties = (JCSMPChannelProperties) properties
					.getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES);
		        
		        chProperties.setConnectRetries(10);
		        chProperties.setConnectTimeoutInMillis(1000);
		        chProperties.setReconnectRetries(2);
		        chProperties.setReconnectRetryWaitInMillis(3000);

				session = JCSMPFactory.onlyInstance().createSession(properties, null, new PrintingSessionEventHandler());			
				session.connect();
				
				// Acquire a message producer.
				prod = session.getMessageProducer(new PrintingPubCallback());
				log.info("Session:"+session.getSessionName());
				log.info("Aquired message producer:"+prod);
				
				
				// Acquire a message consumer.
				cons = session.getMessageConsumer(new XMLMessageListener() {
		            public void onReceive(BytesXMLMessage message) {

		                if (message.getReplyTo() != null) {
		                	
		                	// Assuming this is a portfolio request message then...
		                	
		                	String jsonRequestString = ((TextMessage) message).getText();
		                	JSONParser jsonParser = new JSONParser();
		                	
		                	JSONObject jsonRequest;
							try {
								jsonRequest = (JSONObject) jsonParser.parse(jsonRequestString);
								
								log.info("Received request, generating response. " + jsonRequest);
			                    
			                    TextMessage reply = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
			                    
			                    String account = (String)jsonRequest.get("account");
			                    
			                    if (account != null) {
			                    	String text = getPortfolioMessage((String)jsonRequest.get("account"));
				                    reply.setText(text);

				                    try {
				                        prod.sendReply(message, reply);
				                    } catch (JCSMPException e) {
				                        log.error("Error sending reply. " + e.getMessage());
				                    }
			                    }
			                    else
			                    {
			                    	log.debug("No account number provided, request ignored.");
			                    }
			                    								
								
							} catch (ParseException e1) {
								// do nothing
								log.debug("Failed to parse json: " + e1.getMessage());
							}
		                	
		                    
		                } 
		                else 
		                {
		                	
		                	// Assuming this is a settlement update message then...		                	
		                	String jsonMessageString = ((TextMessage) message).getText();
		                	JSONParser jsonParser = new JSONParser();
		                	
		                	JSONObject jsonMessage;
							try {
								jsonMessage = (JSONObject) jsonParser.parse(jsonMessageString);
								String account = (String)jsonMessage.get("account");
								String instrument = (String)jsonMessage.get("instrument");
								int qty = Integer.parseInt((String)jsonMessage.get("qty"));
								String price = (String)jsonMessage.get("price");
								String buyOrSell = (String)jsonMessage.get("side");
								
								log.info("Received settlement update for account: " + account);
			                    
			                    if (account != null) {
			                    	updatePortfolio(account, instrument, qty, price, buyOrSell);
			                    }
			                    else
			                    {
			                    	log.debug("No account number provided, request ignored.");
			                    }

							} catch (ParseException e1) {
								// do nothing
								log.debug("Failed to parse json: " + e1.getMessage());
							}
		                }
		            }

		            public void onException(JCSMPException e) {
		                System.out.printf("Consumer received exception: %s%n", e);
		            }
		        });
				
				log.info("Aquired message consumer:"+cons);
				
				Topic topic1 = JCSMPFactory.onlyInstance().createTopic(topicPortfolioFetch);
				Topic topic2 = JCSMPFactory.onlyInstance().createTopic(topicSettlementUpdates);
				
				
				session.addSubscription(topic1);
				session.addSubscription(topic2);
				
		        cons.start();

		        // Consume-only session is now hooked up and running!
		        log.info("Listening for request messages on topic " + topic1);
		        log.info("Listening for settlement update messages on topic " + topic2);
			
			} catch (Exception ex) {
				log.error("Encountered an Exception: " + ex.getMessage());
				log.error(ex.getStackTrace());
				finish(1);
			}
		}
		
		protected void finish(final int status) {
			
			if (session != null) {
				session.closeSession();
			}
			System.exit(status);
		}	
		

	}
	
	public class PrintingSessionEventHandler implements SessionEventHandler {
        public void handleEvent(SessionEventArgs event) {
        	log.warn("Received Session Event "+event.getEvent()+ " with info "+event.getInfo());
        }
	}

	public class PrintingPubCallback implements JCSMPStreamingPublishEventHandler {
		public void handleError(String messageID, JCSMPException cause, long timestamp) {
			log.error("Error occurred for message: " + messageID);
			cause.printStackTrace();
		}

		public void responseReceived(String messageID) {
			log.info("Response received for message: " + messageID);
		}
	}

	
		
		
}

