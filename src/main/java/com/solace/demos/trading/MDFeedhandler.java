package com.solace.demos.trading;

import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Properties;

import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishEventHandler;
import com.solacesystems.jcsmp.SessionEventArgs;
import com.solacesystems.jcsmp.SessionEventHandler;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessage;
import com.solacesystems.jcsmp.XMLMessageProducer;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

public class MDFeedhandler {
	
	// logging interface
	private static Logger log = null;
	
	private DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
	
	// logging initialiser
	static
	{
		log = Logger.getLogger(MDFeedhandler.class);
	}
	
	public static double FREQUENCY_IN_SECONDS = 5;

	public static String SOLACE_IP_PORT = null;
	public static String SOLACE_VPN = null;	
	public static String SOLACE_CLIENT_USERNAME = null;
	public static String SOLACE_PASSWORD = null;
	public static String EXCHANGE = null;
	public static String INSTRUMENTS = null;
	public static String TOPIC_PREFIX = null;
	
	public static Properties instrumentsList = null;
	public static Properties instrumentsListOriginal = null;
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		// If there is not a log4j.properties in the current working directory, use the one bundled in jar.
		checkForLog4jConfigFile();
		
		log.info("Initializing MDFeedhandler version 1.0.5");
		
		MDFeedhandler mdPublisher = new MDFeedhandler();
		if(mdPublisher.parseArgs(args) ==1 || mdPublisher.validateParams() ==1) {
			log.error(mdPublisher.getCommonUsage());
		}
		else {
			MDDStreamerThread hwPubThread = mdPublisher.new MDDStreamerThread();
			hwPubThread.start();
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
		str += "\t -t TOPIC_PREFIX Prefix of the Topic to be used to send messages\n";		
		str += "\t -i INSTRUMENTS  Properties file containing instruments\n";
		str += "\t[-p PASSWORD]    Authentication password\n";
		str += "\t[-f FREQUENCY]   Frequency of publish in seconds (default: 5)\n";
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
				} else if (args[i].equals("-t")) {
					i++;
					TOPIC_PREFIX = args[i].toUpperCase();	
				} else if (args[i].equals("-i")) {
					i++;
					INSTRUMENTS = args[i];		
				} else if (args[i].equals("-v")) {
					i++;
					SOLACE_VPN = args[i];
				} else if (args[i].equals("-f")) {
					i++;
					try {
					FREQUENCY_IN_SECONDS = Double.parseDouble(args[i]);
					}
					catch (NumberFormatException nfe) {
						System.out.println("FREQUENCY_IN_SECONDS - NumberFormatException");
						return 1; // err: print help
					}
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
		if (TOPIC_PREFIX == null) return 1;
		if (INSTRUMENTS == null) return 1;
		return 0;
	}
	
	/**
	 * Thread class to generate pixels to move every "n" seconds, where "n" is the Frequency In Seconds passed
	 * to the thread upon instantiation
	 * @param args
	 */	
	class MDDStreamerThread extends Thread {
		
		JCSMPSession session = null;
	    XMLMessageProducer prod = null;   
		
		public void run() {

			while (true){
				
				try {

					initInstrumentsList();
					initSolace();
					//generate market data updates to a topic of the format:
					// MD/<EXCHANGE-NAME>/<INSTRUMENT>/TRADES
				
					String topicString = null;
					String payload = null;
					
					Random random = new Random();
					
					String directionString = "";
					int directionInt = 0;
					
					double change;
					double price;
					double priceReset;
					double pricePrevious;

					@SuppressWarnings("unchecked")
					Enumeration<String> enumInstruments = (Enumeration<String>) instrumentsList.propertyNames();
					
				    while (enumInstruments.hasMoreElements()) 
				    {
				    	// (1) Iterate through the instruments list
				    	String instrument = enumInstruments.nextElement();
				    	
				    	// (1a) Is this an instrument to get an update this time round?
				    	if (random.nextBoolean()) {
				    		
				    		// (2) Should the price go up or down?
						    directionString = (random.nextBoolean()) ? "+" : "-";
						    directionInt = Integer.parseInt(directionString + "1");
						    
						    // (3) Work out the price change
						    // Vary the PX between 0% to 10% of the instrument price 
						    change = directionInt * random.nextDouble() * (Double.parseDouble(instrumentsList.getProperty(instrument)) * 0.2);
						    log.info("PX="+Double.parseDouble(instrumentsList.getProperty(instrument))+"\tchange="+(Double.parseDouble(instrumentsList.getProperty(instrument)) * 0.2));
						    
						    // (4) Create the new price and save that for the next update to calculate new price from
						    price = Double.parseDouble(instrumentsList.getProperty(instrument)) + change;
						    instrumentsList.setProperty(instrument, Double.toString(price));
						    
						    // (4a) Every so often (20% of the time), revert back to the baseline price for a given instrument to ensure multiple exchanges are not drifting apart
						    if (random.nextDouble() < 0.20 ){
						    	// Apply the change to the baseline price
						    	pricePrevious = Double.parseDouble(instrumentsListOriginal.getProperty(instrument));
						    	priceReset = pricePrevious + change;
						    	
						    	
						    	// But also work out what the new change is with this price and the last price sent
						    	directionString = (priceReset > pricePrevious) ? "+" : "-";
						    	
						    	log.debug("Priced back from baseline for instrument: " + instrument + ". " + priceReset + " instead of " + price);

						    	// Now save this price back
						    	instrumentsList.setProperty(instrument, Double.toString(priceReset));
						    	price = priceReset;
						    } 
						      				 
						    // (5) Define the topic for this instrument's update
						    topicString = MDFeedhandler.TOPIC_PREFIX + "/" + MDFeedhandler.EXCHANGE + "/" + instrument ;
						      
						    // (6) Create the JSON payload from the instrument, price and the up/down direction
						    payload = createTradeUpdateMessage(instrument, price, directionString);
						      
						    log.info("topicString="+topicString+"\tmessage="+payload);
								
							// (7) Publish the update message
						    publishToSolace(topicString, payload);

				    	}
				    	
				    					      
				    }
					
				    log.debug("===============");
					//log.error("Now sleeping for "+(int)(MDDStreamer.FREQUENCY_IN_SECONDS * 1000)+" milliseconds");
				    
				    //Randomize the sleep time
					sleep((int)(MDFeedhandler.FREQUENCY_IN_SECONDS * 1000 * random.nextDouble()));
					
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			
		}
		
		public String createTradeUpdateMessage (String instrument, double price, String direction){
			
			StringBuffer jsonMsg = new StringBuffer();
			
			DecimalFormat df_3dec = new DecimalFormat("#.###");
			DecimalFormat df_nodec = new DecimalFormat("###,###");
			
			String exchange = MDFeedhandler.EXCHANGE;

			LocalDateTime now = LocalDateTime.now();
			   
			jsonMsg.append("[{\"Sec\": \"").append(instrument);
			jsonMsg.append("\", \"Ex\": \"").append(exchange);
			jsonMsg.append("\", \"Price\": \"").append(df_3dec.format(price));
			jsonMsg.append("\", \"Qty\": \"").append(df_nodec.format(Math.random() * 10000));
			jsonMsg.append("\", \"Chg\": \"").append(direction);
			jsonMsg.append("\", \"DateTime\": \"").append(dtf.format(now));
			jsonMsg.append("\"}]");
			
			return jsonMsg.toString();
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
					msg.setElidingEligible(true); 
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
	            
	            // This will gold the original price to act as a baseline
	            instrumentsListOriginal = new Properties();
	            instrumentsListOriginal.putAll(instrumentsList);

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
				
				
				JCSMPProperties properties = new JCSMPProperties();

				properties.setProperty(JCSMPProperties.HOST, SOLACE_IP_PORT);
				properties.setProperty(JCSMPProperties.USERNAME, SOLACE_CLIENT_USERNAME);
				properties.setProperty(JCSMPProperties.VPN_NAME, SOLACE_VPN);
				properties.setProperty(JCSMPProperties.PASSWORD, SOLACE_PASSWORD);
				
				properties.setBooleanProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE, false);
				properties.setBooleanProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);
				
			       // Channel properties
		        JCSMPChannelProperties chProperties = (JCSMPChannelProperties) properties
					.getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES);
		        
		        chProperties.setConnectRetries(120);
		        chProperties.setConnectTimeoutInMillis(1000);
		        chProperties.setReconnectRetries(-1);
		        chProperties.setReconnectRetryWaitInMillis(3000);

				session =  JCSMPFactory.onlyInstance().createSession(properties, null, new PrintingSessionEventHandler());
				
				log.info(""
						+ " to create session.");
				session.connect();
				
				// Acquire a message producer.
				prod = session.getMessageProducer(new PrintingPubCallback());
				log.info("Session:"+session.getSessionName());
				log.info("Aquired message producer:"+prod);
			
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

