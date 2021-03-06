package kg.apc.jmeter.jmxmon;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Hashtable;
import java.util.List;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import kg.apc.jmeter.JMeterPluginsUtils;
import kg.apc.jmeter.vizualizers.CorrectedResultCollector;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleSaveConfiguration;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

/**
 *
 * @author Lars Holmberg
 */
public class JMXMonCollector
        extends CorrectedResultCollector
        implements Runnable, JMXMonSampleGenerator {

    private static boolean autoGenerateFiles = false;
    private static final String JMXMON = "JmxMon";
    private static final Logger log = LoggingManager.getLoggerForClass();
    public static final String DATA_PROPERTY = "samplers";
    private int interval;
    private Thread workerThread = null;
    protected List<JMXMonSampler> jmxMonSamplers = new ArrayList<JMXMonSampler>();
    private static String autoFileBaseName = null;
    private static int counter = 0;
    private static String workerHost = null;

    static {
        autoGenerateFiles = (JMeterUtils.getPropDefault("forceJmxMonFile", "false")).trim().equalsIgnoreCase("true");
    }

    private static synchronized String getAutoFileName() {
        String ret = "";
        counter++;
        if (autoFileBaseName == null) {
            Calendar now = Calendar.getInstance();
            SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd-HHmmss");
            autoFileBaseName = "jmxMon_" + formatter.format(now.getTime());
        }
        ret = ret + autoFileBaseName;
        if (counter > 1) {
            ret = ret + "_" + counter;
        }
        ret = ret + ".csv";

        return ret;
    }

    public JMXMonCollector() {
        interval = JMeterUtils.getPropDefault("jmeterPlugin.jmxmon.interval", 1000);
    }

    public void setData(CollectionProperty rows) {
        setProperty(rows);
    }

    public JMeterProperty getSamplerSettings() {
        return getProperty(DATA_PROPERTY);
    }

    @Override
    public synchronized void run() {
        try {
            while (true) {
                processConnectors();
                this.wait(interval);
            }
        } catch (InterruptedException ex) {
            log.debug("Monitoring thread was interrupted", ex);
        }
    }

    //ensure we start only on one host (if multiple slaves)
    private synchronized static boolean isWorkingHost(String host) {
       if(workerHost == null) {
          workerHost = host;
          return true;
       } else {
          return host.equals(workerHost);
       }
    }

    @Override
    public void testStarted(String host) {

        if(!isWorkingHost(host)) {
           return;
        }

        //ensure the data will be saved
        if (getProperty(FILENAME) == null || getProperty(FILENAME).getStringValue().trim().length() == 0) {
            if (autoGenerateFiles) {
                setupSaving(getAutoFileName());
            } else {
                log.info("JmxMon metrics will not be recorded! Please specify a file name in the gui or run the test with -JforceJmxMonFile=true");
            }
        }
        try {
            initiateConnectors();
        } catch (MalformedURLException ex) {
            //throw new RuntimeException(ex);
            log.error("Malformed JMX url", ex);
        } catch (IOException ex) {
            log.error("IOException reading JMX", ex);
        }

        workerThread = new Thread(this);
        workerThread.start();

        super.testStarted(host);
    }

    private void setupSaving(String fileName) {
        SampleSaveConfiguration config = getSaveConfig();
        JMeterPluginsUtils.doBestCSVSetup(config);
        setSaveConfig(config);
        setFilename(fileName);
        log.info("JMXMon metrics will be stored in " + new File(fileName).getAbsolutePath());
    }

    @Override
    public void testEnded(String host) {
        if(workerThread == null) {
           return;
        }
        workerHost = null;
        workerThread.interrupt();
        shutdownConnectors();

        //reset autoFileName for next test run
        autoFileBaseName = null;
        counter = 0;
        super.testEnded(host);
    }

    private void initiateConnectors() throws MalformedURLException, IOException {
        JMeterProperty prop = getSamplerSettings();
        jmxMonSamplers.clear();
        if (!(prop instanceof CollectionProperty)) {
            log.warn("Got unexpected property: " + prop);
            return;
        }
        CollectionProperty rows = (CollectionProperty) prop;

        for (int i = 0; i < rows.size(); i++) {
            ArrayList<Object> row = (ArrayList<Object>) rows.get(i).getObjectValue();
            String label = ((JMeterProperty) row.get(0)).getStringValue();
            String jmxUrl = ((JMeterProperty) row.get(1)).getStringValue();
            String username = ((JMeterProperty) row.get(2)).getStringValue();
            String password = ((JMeterProperty) row.get(3)).getStringValue();            
            String objectName = ((JMeterProperty) row.get(4)).getStringValue();
            String attribute = ((JMeterProperty) row.get(5)).getStringValue();
            boolean isDelta = ((JMeterProperty) row.get(6)).getBooleanValue();
            
            JMXServiceURL u = new JMXServiceURL(jmxUrl);
            Hashtable attributes = new Hashtable();
            String[] buffer = { username, password };
            attributes.put("jmx.remote.credentials", (String[]) buffer);
            
            initiateConnector(u, attributes, label, isDelta, objectName, attribute);
        }
    }

    protected void initiateConnector(JMXServiceURL u, Hashtable attributes, String name, boolean delta, String objectName, String attribute) throws MalformedURLException, IOException {
        MBeanServerConnection conn = JMXConnectorFactory.connect(u,attributes).getMBeanServerConnection();
        jmxMonSamplers.add(new JMXMonSampler(conn, name, objectName, attribute, delta));
    }


    private void shutdownConnectors() {
        jmxMonSamplers.clear();
    }

    protected void processConnectors() {
        for (JMXMonSampler sampler: jmxMonSamplers) {
            sampler.generateSamples(this);
        }
    }

    @Override
    public void sampleOccurred(SampleEvent event) {
        // just dropping regular test samples
    }
    
    protected void jmxMonSampleOccurred(SampleEvent event) {
        super.sampleOccurred(event);
    }

    @Override
    public void generateSample(double value, String label) {
        JMXMonSampleResult res = new JMXMonSampleResult();
        res.setSampleLabel(label);
        res.setValue(value);
        res.setSuccessful(true);
        SampleEvent e = new SampleEvent(res, JMXMON);
        jmxMonSampleOccurred(e);
    }
}
