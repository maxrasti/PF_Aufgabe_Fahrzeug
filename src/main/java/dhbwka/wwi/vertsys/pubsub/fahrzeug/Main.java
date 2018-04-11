/*
 * Copyright © 2018 Dennis Schulmeister-Zimolong
 * 
 * E-Mail: dhbw@windows3.de
 * Webseite: https://www.wpvs.de/
 * 
 * Dieser Quellcode ist lizenziert unter einer
 * Creative Commons Namensnennung 4.0 International Lizenz.
 */
// Frank Föcking Yannik Lischka Max Rastetter

package dhbwka.wwi.vertsys.pubsub.fahrzeug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/**
 * Hauptklasse unseres kleinen Progrämmchens.
 *
 * Mit etwas Google-Maps-Erfahrung lassen sich relativ einfach eigene
 * Wegstrecken definieren. Man muss nur Rechtsklick auf einen Punkt machen und
 * "Was ist hier?" anklicken, um die Koordinaten zu sehen. Allerdings speichert
 * Goolge Maps eine Nachkommastelle mehr, als das ITN-Format erlaubt. :-)
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // Fahrzeug-ID abfragen
        String vehicleId = Utils.askInput("Beliebige Fahrzeug-ID", "postauto");

        // Zu fahrende Strecke abfragen
        File workdir = new File("./waypoints");
        String[] waypointFiles = workdir.list((File dir, String name) -> {
            return name.toLowerCase().endsWith(".itn");
        });

        System.out.println();
        System.out.println("Aktuelles Verzeichnis: " + workdir.getCanonicalPath());
        System.out.println();
        System.out.println("Verfügbare Wegstrecken");
        System.out.println();

        for (int i = 0; i < waypointFiles.length; i++) {
            System.out.println("  [" + i + "] " + waypointFiles[i]);
        }

        System.out.println();
        int index = Integer.parseInt(Utils.askInput("Zu fahrende Strecke", "0"));
        
        // TODO(Frank): Methode parseItnFile() unten ausprogrammieren
        List<WGS84> waypoints = parseItnFile(new File(workdir, waypointFiles[index]));

        // Adresse des MQTT-Brokers abfragen
        String mqttAddress = Utils.askInput("MQTT-Broker", Utils.MQTT_BROKER_ADDRESS);

        // TODO(Yannik): Sicherstellen, dass bei einem Verbindungsabbruch eine sog.
        // LastWill-Nachricht gesendet wird, die auf den Verbindungsabbruch
        // hinweist. Die Nachricht soll eine "StatusMessage" sein, bei der das
        // Feld "type" auf "StatusType.CONNECTION_LOST" gesetzt ist.
        //
        // Die Nachricht muss dem MqttConnectOptions-Objekt übergeben werden
        // und soll an das Topic Utils.MQTT_TOPIC_NAME gesendet werden.
        StatusMessage letzterWille = new StatusMessage();
        
        letzterWille.vehicleId = vehicleId;
        letzterWille.message =  "Verbindung nicht mehr Verfügbar";
        letzterWille.type = StatusType.CONNECTION_LOST;

        String clientId = "Fahrer(Fahrzeug) - " + System.currentTimeMillis();

        //Verbindung zum MQTT-Broker herstellen.
        MqttConnectOptions mqttOptions = new MqttConnectOptions();
        mqttOptions.setCleanSession(true);

        mqttOptions.setWill(Utils.MQTT_TOPIC_NAME, letzterWille.toJson(), 0, false);

        MqttClient client = new MqttClient(mqttAddress, clientId);
        client.connect(mqttOptions);

        // TODO(Max): Statusmeldung mit "type" = "StatusType.VEHICLE_READY" senden.
        // Die Nachricht soll soll an das Topic Utils.MQTT_TOPIC_NAME gesendet
        // werden.
        StatusMessage statusMeldung = new StatusMessage();
        statusMeldung.vehicleId = vehicleId;
        statusMeldung.type = StatusType.VEHICLE_READY;

        client.publish(Utils.MQTT_TOPIC_NAME, statusMeldung.toJson(), 0, false);

        // TODO(Frank): Thread starten, der jede Sekunde die aktuellen Sensorwerte
        // des Fahrzeugs ermittelt und verschickt. Die Sensordaten sollen
        // an das Topic Utils.MQTT_TOPIC_NAME + "/" + vehicleId gesendet werden.
        Vehicle vehicle = new Vehicle(vehicleId, waypoints);
        vehicle.startVehicle();

        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                SensorMessage sensMessage = vehicle.getSensorData();

                byte[] json = sensMessage.toJson();
                
                String topic = Utils.MQTT_TOPIC_NAME + "/" + vehicleId;
                System.out.println(topic + " -> " + new String(json, StandardCharsets.UTF_8));

                try {
                    MqttMessage message = new MqttMessage(json);
                    client.publish(topic, message);
                } catch (MqttException ex) {
                    System.out.println("Mqtt Exception in run()");
                    Utils.logException(ex);
                }
            }
        };

        Timer intervall = new Timer(true);
        intervall.scheduleAtFixedRate(task, 0, 1000);

        // Warten, bis das Programm beendet werden soll
        Utils.fromKeyboard.readLine();

        vehicle.stopVehicle();

        // TODO(Max): Oben vorbereitete LastWill-Nachricht hier manuell versenden,
        // da sie bei einem regulären Verbindungsende nicht automatisch
        // verschickt wird.
        //
        // Anschließend die Verbindung trennen und den oben gestarteten Thread
        // beenden, falls es kein Daemon-Thread ist.
        
        client.publish(Utils.MQTT_TOPIC_NAME, letzterWille.toJson(), 0, false);

        /**Verbindung wird getrennt
         * 
         */
        client.disconnect();
        
       
        /**gestarteten Thread wird beendet
         * 
         */
        System.exit(0);

    }

    /**
     * Öffnet die in "filename" übergebene ITN-Datei und extrahiert daraus die
     * Koordinaten für die Wegstrecke des Fahrzeugs. Das Dateiformat ist ganz
     * simpel:
     *
     * <pre>
     * 0845453|4902352|Point 1 |0|
     * 0848501|4900249|Point 2 |0|
     * 0849295|4899460|Point 3 |0|
     * 0849796|4897723|Point 4 |0|
     * </pre>
     *
     * Jede Zeile enthält einen Wegpunkt. Die Datenfelder einer Zeile werden
     * durch | getrennt. Das erste Feld ist die "Longitude", das zweite Feld die
     * "Latitude". Die Zahlen müssen durch 100_000.0 geteilt werden.
     *
     * @param file ITN-Datei
     * @return Liste mit Koordinaten
     * @throws java.io.IOException
     */
    public static List<WGS84> parseItnFile(File file) throws IOException {
        List<WGS84> waypoints = new ArrayList<>();

        // TODO(Yannik): Übergebene Datei parsen und Liste "waypoints" damit füllen
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
        String text;

        while ((text = bufferedReader.readLine()) != null) {
            String[] stringArray = text.split("\\|");
            
            if (stringArray.length >= 2) {
                String longitude = stringArray[0];
                String latitude = stringArray[1];
                WGS84 waypoint = new WGS84();
                
                try {
                    waypoint.longitude = Double.parseDouble(longitude) / 100_000.0;
                    waypoint.latitude = Double.parseDouble(latitude) / 100_000.0;
                    waypoints.add(waypoint);
                } catch (NumberFormatException ex) {
                    System.out.println("NumberFormatException in List");
                    Utils.logException(ex);
                }
            }
        }
        return waypoints;
    }

}
