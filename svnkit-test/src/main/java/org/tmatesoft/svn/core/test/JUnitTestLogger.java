package org.tmatesoft.svn.core.test;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Iterator;

import org.tmatesoft.svn.core.internal.util.SVNXMLUtil;

public class JUnitTestLogger extends AbstractTestLogger {
    
    private static NumberFormat ourTestNumberFormat = new DecimalFormat("000");
    
    private File myResultDirectory;
    
    private int myFailuresCount;
    private int myTestsCount;
    private long myLastTime;
    private long mySuiteTime;
    private List myTests;
    
    public JUnitTestLogger(File resultDirectory) {
        myResultDirectory = resultDirectory;
    }

    public void startTests(Properties configuration) throws IOException {
    }

    public void startServer(String name, String url) {
    }

    public void startSuite(String suiteName) {
        myLastTime = System.currentTimeMillis();
        mySuiteTime = myLastTime;
        myTests = new LinkedList();
        myTestsCount = 0;
        myFailuresCount = 0;
    }

    public void handleTest(TestResult test) {
        TestInfo info = new TestInfo();
        info.name = ourTestNumberFormat.format(test.getID()) + " [" + test.getName() + " ]";
        info.time = System.currentTimeMillis() - myLastTime;
        info.isFailed = !test.isPass();
        if (!test.isPass() && test.getOutput() != null) {
            info.output = test.getOutput().toString();
        }
        if (!test.isPass()) {
            myFailuresCount++;
        }
        myTestsCount++;
        myLastTime = System.currentTimeMillis();
        
        myTests.add(info);
    }

    public void endSuite(String suiteName) {
        
        Map suiteAttributes = new HashMap();
        suiteAttributes.put("errors", "0");
        suiteAttributes.put("failures", Integer.toString(myFailuresCount));
        suiteAttributes.put("tests", Integer.toString(myTestsCount));
        suiteAttributes.put("time", getTimeString(System.currentTimeMillis() - mySuiteTime));
        suiteAttributes.put("name", suiteName);
        
        StringBuffer xml = new StringBuffer();
        xml = SVNXMLUtil.addXMLHeader(xml);
        xml = SVNXMLUtil.openXMLTag(null, "testsuite", SVNXMLUtil.XML_STYLE_NORMAL, suiteAttributes, xml);
        
        if (myTests != null) {
            for (Iterator tests = myTests.iterator(); tests.hasNext();) {
                TestInfo test = (TestInfo) tests.next();
                Map testAttributes = new HashMap();
                testAttributes.put("classname", suiteName);
                testAttributes.put("name", test.name);
                testAttributes.put("time", getTimeString(test.time));
                
                xml = SVNXMLUtil.openXMLTag(null, "testcase", !test.isFailed ? (SVNXMLUtil.XML_STYLE_SELF_CLOSING | SVNXMLUtil.XML_STYLE_PROTECT_CDATA): SVNXMLUtil.XML_STYLE_NORMAL, 
                        testAttributes, xml);
                if (test.isFailed) {
                    Map failureAttributes = new HashMap();
                    failureAttributes.put("type", "org.tmatesoft.test.python");
                    if (test.output != null) {
                        xml = SVNXMLUtil.openCDataTag(null, "failure", test.output, failureAttributes, xml);
                    } else {
                        xml = SVNXMLUtil.openXMLTag(null, "failure", SVNXMLUtil.XML_STYLE_SELF_CLOSING, failureAttributes, xml);
                    }
                    xml = SVNXMLUtil.closeXMLTag(null, "testcase", xml, true);
                }
            }
        }
        xml = SVNXMLUtil.closeXMLTag(null, "testsuite", xml);
        
        Writer writer = openSuiteFile(suiteName);
        if (writer != null) {
            try {
                writer.write(xml.toString());
            } catch (IOException e) {
            } finally {
                    try {
                        writer.close();
                    } catch (IOException e) {
                    }
            }
        }
    }

    private String getTimeString(long l) {
        String str = Long.toString(l);
        if (str.length() > 3) {
            return str.substring(0, str.length() - 3) + "." + str.substring(str.length() - 3, str.length());
        } else {
            return "0." + str;
        }
    }

    public void endServer(String name, String url) {
    }

    public void endTests(Properties configuration) {
    }
    
    private Writer openSuiteFile(String suiteName) {
        myResultDirectory.mkdirs();
        File file = new File(myResultDirectory, getSuiteFileName(suiteName));
        Writer w = null;
        try {
            w = new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(file)), "UTF-8");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return w;
    }

    private String getSuiteFileName(String suiteName) {
        return "TEST-" + suiteName + ".xml";
    }
    
    private static class TestInfo {
        public String output;
        String name;
        long time;
        boolean isFailed;
    }

}
