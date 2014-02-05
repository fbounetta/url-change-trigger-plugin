package com.redfin.hudson;

import static org.junit.Assert.*;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.ParameterDefinition;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.StaplerRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import com.redfin.hudson.UrlChangeTrigger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class UrlChangeTriggerPluginTest {
	
	private static final Logger logger = Logger.getLogger(UrlChangeTriggerPluginTest.class.toString());
	
	private int port = 9090;
	private String url = "http://localhost:"+port+"/";
	private int timeout = 12000;
	private String confSpec = "*/10 * * * *";
	
    @Before
    public void setUp() throws IOException {
    }
    
    @After
    public void tearUp() throws IOException {
    }
    
    @Test(expected=ConnectException.class)
	public void testConnectTimeout() throws Exception {
		UrlChangeTrigger urlChangeTrigger = new UrlChangeTrigger(url, timeout, confSpec);
		assertNotNull(urlChangeTrigger);
        urlChangeTrigger.getInputStream(timeout);
	}
	
	
	//@Test(expected=SocketTimeoutException.class)
	public void testReadTimeout() throws Exception {
		//Start the server
		IdleAndWriteStreamThread client = new IdleAndWriteStreamThread(port, timeout*2);
        new Thread(client).start();
		
		UrlChangeTrigger urlChangeTrigger = new UrlChangeTrigger(url, timeout, confSpec);
        assertNotNull(urlChangeTrigger);
        urlChangeTrigger.getInputStream(timeout);
	}

	//@Test
	public void testReadSuccess() throws Exception {
		int timeout = 35000;
		//String url = "http://artifactory-slc.oraclecorp.com/artifactory/api/search/latestVersion?g=manifest.integration.prerelease&a=askernel-manifest&remote=1&repos=fmw-virtual";
		UrlChangeTrigger urlChangeTrigger = new UrlChangeTrigger(url, timeout, confSpec);
        assertNotNull(urlChangeTrigger);
        //Start the server
        WriteStreamThread client = new WriteStreamThread(port, timeout*2);
        new Thread(client).start();
        InputStream in = urlChangeTrigger.getInputStream(timeout);
	    assert(in!=null);
	    Util.getDigestOf(in);
	    
	}
	
	//@Test
	public void testDescriptorConfiguration() throws Exception {
		//TODO Build a request
		StaplerRequest req= null;//new RequestImpl();
		//Build formData
		String formData = "{\"parameterized\":{\"parameter\":{\"system_message\":\"\",\"numExecutors\":\"2\",\"quiet_period\":\"5\",\"retry_count\":\"0\",\"usageStatisticsCollected\":{},\"globalNodeProperties\":{},\"hudson-tasks-Shell\":{\"shell\":\"\"},\"hudson-tasks-Mailer\":{\"smtpServer\":\"\",\"defaultSuffix\":\"\",\"adminAddress\":\"address not configured yet <nobody@nowhere>\",\"url\":\"\",\"useSsl\":false,\"smtpPort\":\"\",\"charset\":\"UTF-8\"},\"com-redfin-hudson-UrlChangeTrigger\":{\"defaultTimeout\":\"25\",\"maxTimeout\":\"30\",\"defaultConfSpec\":\"*/10 * * * *\",\"minConfSpec\":\"*/5 * * * *\"}}}}";
		JSONObject json = JSONObject.fromObject(formData);
		List<ParameterDefinition> descriptor = Descriptor.newInstancesFromHeteroList(
                req, json, "parameter", ParameterDefinition.all());
		//descriptor
	}
	
	private String streamToString(InputStream in) throws IOException {
	      StringBuilder out = new StringBuilder();
	      BufferedReader br = new BufferedReader(new InputStreamReader(in));
	      for(String line = br.readLine(); line != null; line = br.readLine()) 
	        out.append(line);
	      br.close();
	      return out.toString();
	}
	
	private class WriteStreamThread implements Runnable {
		ServerSocket listener=null;
		int idle;
		public WriteStreamThread(int port, int idle) {
			this.idle = idle;
			try {
				this.listener = new ServerSocket(port);
			} catch (IOException e) {}
        }
		
		public void run() {
			Socket socket=null;
			try {
				System.out.println("------------------------------------------------");
				System.out.println("Waiting for connection...");		 
				socket = listener.accept();
			 	System.out.println("got connection and will start writing to the stream.");
			 	PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
				String date = new Date().toString();
				out.println(date);
				
			} catch (Exception e) {
			} finally {
				try {
					if(socket!=null)
						socket.close();
					if(listener!=null)
						listener.close();
				} catch (IOException e) {}
			}
		}
    }
	
	private class IdleAndWriteStreamThread implements Runnable {
		ServerSocket listener=null;
		int idle;
		public IdleAndWriteStreamThread(int port, int idle) {
			this.idle = idle;
			try {
				this.listener = new ServerSocket(port);
			} catch (IOException e) {}
        }
		
		public void run() {
			Socket socket = null;
			try {
				 System.out.println("------------------------------------------------");
				 System.out.println("Waiting for connection...");		 
				 socket = listener.accept();
				 System.out.println("got connection but i will wait "+idle+" milliseconds before it writes to the stream");
				 Thread.sleep(idle);
				 PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
				 String date = new Date().toString();
				 System.out.println("returning date:"+date);
				 out.println(date);
				
			} catch (Exception e) {
			} finally {
				try {
					if(socket!=null)
						socket.close();
					if(listener!=null)
						listener.close();
				} catch (IOException e) {}
			}
		}
    }
	
	/**
	* Used internally.
	*/
	private class WriteBigContentStreamThread implements Runnable {
		ServerSocket listener=null;
		int idle;
		public WriteBigContentStreamThread(int port, int idle) {
			this.idle = idle;
			try {
				this.listener = new ServerSocket(port);
			} catch (IOException e) {}
        }
		
		public void run() {
			Socket socket = null;
			try {
				System.out.println("------------------------------------------------");
				 System.out.println("Waiting for connection...");		 
				 socket = listener.accept();
				 OutputStream ou = socket.getOutputStream();
				 System.out.println("Got connection and will start writing data to the output stream"); 
				 PrintWriter out = new PrintWriter(ou);
				 for(int i=0;i<10000;i++) {
					 String date = new Date().toString();
					 out.write(date);
					 out.write("***");
					 Thread.sleep(800);
				 }
			} catch (Exception e) {
			} finally {
				try {
					if(socket!=null)
						socket.close();
					if(listener!=null)
						listener.close();
				} catch (IOException e) {}
			}
		}
    }
	
	private class InterruptThread implements Runnable {     
		HttpURLConnection con;

        public InterruptThread(HttpURLConnection con) {
            this.con = con;
        }

        public void run() {
            try {
                Thread.sleep(con.getConnectTimeout()*2); 
            } catch (InterruptedException e) {
            }
            con.disconnect();     
        }
    }
}
