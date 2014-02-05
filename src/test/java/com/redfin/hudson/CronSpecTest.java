package com.redfin.hudson;

import static org.junit.Assert.*;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.ParameterDefinition;
import hudson.scheduler.CronTab;
import hudson.scheduler.CronTabList;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.RequestImpl;
import org.kohsuke.stapler.StaplerRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import com.redfin.hudson.UrlChangeTrigger;
import com.redfin.hudson.UrlChangeTrigger.DescriptorImpl;

import org.antlr.runtime.RecognitionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;


@RunWith(Parameterized.class)
public class CronSpecTest {
	
	private static final Logger logger = Logger.getLogger(CronSpecTest.class.toString());
	
	private static int port = 9090;
	private static String url = "http://localhost:"+port+"/";
	private static int timeout = 12000;
	private static String minConfSpec="*/10 * * * *";
	
	private boolean expectedResult;
    private UrlChangeTrigger instance;
    
    public CronSpecTest(boolean expectedResult, UrlChangeTrigger instance) {
        this.expectedResult = expectedResult;
        this.instance = instance;
    }

    @Parameterized.Parameters
    public static Collection generateData() throws Exception {
        return Arrays.asList(new Object[][] {
            {true, new UrlChangeTrigger(url, timeout, "*/5 * * * *")},
            {false, new UrlChangeTrigger(url, timeout,"*/10 * * * *")},
            {false, new UrlChangeTrigger(url, timeout,"*/15 * * * *")},
            {true, new UrlChangeTrigger(url, timeout,"*/8 * * * *")},
            {false, new UrlChangeTrigger(url, timeout,"*/8 1 * * *")},
            {false, new UrlChangeTrigger(url, timeout, "*/12 * 1 * *")},
            {false, new UrlChangeTrigger(url, timeout,"*/5 * * 1 *")},
            {false, new UrlChangeTrigger(url, timeout,"5 * * * *")},
            {false, new UrlChangeTrigger(url, timeout,"5 * * 1 *")},
            {true, new UrlChangeTrigger(url, timeout,"*/9 * * * *")}
            //This expression makes the code hang when calling cron.floor (method in CronTab)
            //{false, new UrlChangeTrigger(url, timeout,"*/9 1 2 3 4")}
        });
    }
    
    @Test
    public void testFrequency() throws Exception {
        assertEquals(expectedResult, DescriptorImpl.isMoreFrequentThanMinConfSpec(instance.getConfSpec(), minConfSpec));
    }
}
