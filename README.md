# MD-Feed-Simulator-JAVA
JAVA Market Data Feed Simulator that publishes JSON prices to Solace in a random interval

## Build JAR
To build the JAR you could either import the GRADLE project on a JAVA IDE, or build it using the included Gradle Wrapper
   ```
     ./gradlew jar
   ```
> :warning: Or gradlew.bat on Windows

That command should generate a MarketDataFeedhandler.jar file on the root folder

## Executing JAR

In order to run the simulator you need to specify the following configuration parameters:
+ -h = Solace Broker DNS or IP
+ -v = Name of the VPN to connect to
+ -u = Solace user to connect to the Broker
+ -p = Solace user password
+ -e = ID of the Simulated Exchange (will be part of the Topic)
+ -i = .properties file containing the symbol tickers and their initial prices to be used (look at the example on [/config/instruments.properties](/config/instruments.properties))

Example:
   ```
     ./java -jar MarketDataFeedhandler.jar -h 192.168.100.1:55555 -v Sol_VPN -u Sol_User -p Sol_PWD -e NYSE -i ./instruments_NYSE.properties
   ```

Multiple simulators can be run in paralell (with different config parameters) if you need to simulate multiple exchanges. If using a linux session, simply run them as "background" process

   