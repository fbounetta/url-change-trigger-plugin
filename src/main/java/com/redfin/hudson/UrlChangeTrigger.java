package com.redfin.hudson;

import static hudson.Util.fixNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.BuildableItem;
import hudson.model.Item;
import hudson.scheduler.CronTab;
import hudson.scheduler.CronTabList;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Calendar;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.json.JSONObject;

import org.antlr.runtime.RecognitionException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/** Triggers a build when the data at a particular URL has changed. */
public class UrlChangeTrigger extends Trigger<BuildableItem> {
	
    URL url;
    int timeout; //in seconds
   
    public UrlChangeTrigger(String url) throws MalformedURLException {
        this(new URL(url));
    }
    
    public UrlChangeTrigger(String url, int timeout, String schedule) throws MalformedURLException, RecognitionException {
        this(new URL(url), timeout, schedule);
    }
    
    public UrlChangeTrigger(URL url) {
        this.url = url;
    }
    
    @DataBoundConstructor
    public UrlChangeTrigger(URL url, int timeout, String schedule) throws RecognitionException {
    	super(schedule);
        this.url = url;
        this.timeout = timeout;
    }
    
    @Override
    public void start(BuildableItem project, boolean newInstance) {
    	super.start(project, newInstance);
    	if(StringUtils.isEmpty(spec)) {
    		//Get the default confSpec from descriptor if any
    		if(!StringUtils.isEmpty(getDescriptor().defaultConfSpec)) {
    			try {
   				 this.tabs = CronTabList.create(getDescriptor().defaultConfSpec);
    			} catch (RecognitionException e) {
		        	throw new RuntimeException("Bug! couldn't schedule poll");
				} 	
    		} else {
    			try {
			        this.tabs = CronTabList.create("* * * * *");
		        } catch (RecognitionException e) {
		        	throw new RuntimeException("Bug! couldn't schedule poll");
				}   
    		}
    	} 
    }

    public static final Logger LOGGER = Logger.getLogger(UrlChangeTrigger.class.getName());

    private File getFingerprintFile() {
    	return new File(job.getRootDir(), "url-change-trigger-oldmd5");
    }

    @Override
    public void run() {
    	InputStream is=null;
    	URLConnection con=null;
    	try {
        	LOGGER.log(Level.INFO, "*** Job {0} processing URL {1} with {2} seconds timeout.", new Object[] {job.getDisplayName(), url, timeout});
        	int mill = timeout*1000;
        	con = url.openConnection();
	    	con.setConnectTimeout(mill);
	        con.setReadTimeout(mill);
	        is = con.getInputStream();
	        
	        String currentMd5 = Util.getDigestOf(is);
            if(currentMd5!=null) {
	            String oldMd5;
	            File file = getFingerprintFile();
	            if (!file.exists()) {
	                oldMd5 = "null";
	            } else {
	                oldMd5 = new FilePath(file).readToString().trim();
	            }
	            if (!currentMd5.equalsIgnoreCase(oldMd5)) {
	                LOGGER.log(Level.FINE,
	                        "Differences found in the file {0}. >{1}< != >{2}<",
	                        new Object[]{
	                                url, oldMd5, currentMd5,
	                        });
	
	                FileUtils.writeStringToFile(file, currentMd5);
	                job.scheduleBuild(new UrlChangeCause(url, timeout));
	            }
            }
       } catch (SocketTimeoutException e) {
    	  LOGGER.log(Level.WARNING, "*** READ TIMEOUT: Job {0} processing URL {1} with {2} seconds timeout.\n {3}", new Object[]{job.getDisplayName(), url, timeout, e});
    	  throw new RuntimeException(e);
       } catch (IOException e) {
     	   LOGGER.log(Level.WARNING, "*** I/O Exception: Job {0} processing URL {1} with {2} seconds timeout.\n {3}  ", new Object[]{job.getDisplayName(), url, timeout, e});
           throw new RuntimeException(e);
 	   } finally {
 		   try {
	 		   if(is!=null)
	 			   is.close();
	 		   if(con!=null)
	 			   if(con instanceof HttpURLConnection)
	 				   ((HttpURLConnection) con).disconnect();
 		   } catch (IOException e) {
 			  throw new RuntimeException(e);
 		   }
	   }
        
    }
    
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }
    
    public URL getUrl() {
        return url;
    }
    
    public int getTimeout() {
    	return timeout;
    }

    @Extension
    public static final class DescriptorImpl extends TriggerDescriptor {

    	String defaultConfSpec;
    	String minConfSpec;
    	int defaultTimeout;
    	int maxTimeout;
    	
    	public static final Logger LOGGER = Logger.getLogger(DescriptorImpl.class.getName());
    	
        public DescriptorImpl() {
            super(UrlChangeTrigger.class);
            //Load Global Configuration
            load();
        }
        
        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
        	maxTimeout = getMaxTimeout(formData);
        	defaultTimeout = getDefaultTimeout(formData);
        	minConfSpec = getMinConfSpec(formData);
        	defaultConfSpec = getDefaultConfSpec(formData);
        	save();
        	return super.configure(req, formData);
        }

        @Override
        public boolean isApplicable(Item item) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Build when a URL's content changes";
        }
        
        @Override
        public String getHelpFile() {
            return "/plugin/url-change-trigger/help-whatIsUrlChangeTrigger.html";
        }
        
        @Override
        public UrlChangeTrigger newInstance(StaplerRequest req, JSONObject formData) throws FormException {
        	String url = formData.getString("url");
            int timeout = getTimeout(formData);
            String confSpec = getConfSpec(formData);
            try {
            	return new UrlChangeTrigger(url, timeout, confSpec);
            } catch (MalformedURLException e) {
                throw new FormException("Invalid URL: " + url, e, "");
            } catch (RecognitionException e) {
                throw new FormException("Invalid Schedule: " + confSpec, e, "");
            }
        }
        
        /**
         * Performs syntax check.
         */
        public FormValidation doCheck(@QueryParameter("urlChangeTrigger.url") String url) {
        	try {
                new URL(fixNull(url));
                return FormValidation.ok();
            } catch (MalformedURLException e) {
                return FormValidation.error(e.getMessage());
            }
        }
        
        /**
         * Performs syntax check.
         */
        public FormValidation doCheckConfSpec(@QueryParameter String value) {
        	try {
        		if(!StringUtils.isEmpty(value)) {
	                String msg = CronTabList.create(value).checkSanity();
	                if (msg != null) {
	                    return FormValidation.warning(msg);
	                }
	                //Compare the schedule to the minimum set in Global Configuration if any
	                if(!StringUtils.isEmpty(minConfSpec) && isLessThanMinConfSpec(value, minConfSpec))
	                	return FormValidation.error("Cannot schedule the trigger more frequent than Mimimum Schedule set in Global Configuration ( "+minConfSpec+" ).");
        		} else {
        			if(StringUtils.isEmpty(defaultConfSpec)) 
            			return FormValidation.error("Schedule needs to be specified. Global configuration is missing.");
            		else
            			return FormValidation.ok("If no Schedule is set for this job, it will use the Global Schedule ( "+defaultConfSpec+" ).");
            	}
                return FormValidation.ok();
            } catch (RecognitionException e) {
                return FormValidation.error(e.getMessage());
            }
        }
        
        /**
         * Performs syntax check.
         */
        public FormValidation doCheckDefaultConfSpec(@QueryParameter String value) {
        	try {
        		if(!StringUtils.isEmpty(value)) {
	                String msg = CronTabList.create(value).checkSanity();
	                if (msg != null) {
	                    return FormValidation.warning(msg);
	                }
	                //Compare the schedule to the minimum set in Global Configuration if any
	                if(!StringUtils.isEmpty(minConfSpec) && isLessThanMinConfSpec(value, minConfSpec))
	                	return FormValidation.error("Cannot have Default Schedule more frequent than Minimum Schedule ( "+minConfSpec+" ).");
        		} else {
        			if(!StringUtils.isEmpty(minConfSpec)) 
        				return FormValidation.ok("Default schedule will be set to "+minConfSpec +" if no value is added.");
        			else
        				return FormValidation.ok("Default schedule will be set to Minimum Schedule if no value is added.");
        		}
            } catch (RecognitionException e) {
                return FormValidation.error(e.getMessage());
            }
        	return FormValidation.ok();
        }
        
        public FormValidation doCheckMinConfSpec(@QueryParameter String value) {
        	try {
        		if(!StringUtils.isEmpty(value)) {
	        		String msg = CronTabList.create(fixNull(value)).checkSanity();
		            if (msg != null) {
		            	return FormValidation.warning(msg);
		            }
        		} else {
        			if(!StringUtils.isEmpty(defaultConfSpec))
        				return FormValidation.ok("Minimum schedule will be set to "+defaultConfSpec +" if no value is added.");
        			else
        				return FormValidation.ok("Minimum schedule will be set to default schedule if no value is added.");
        		}
            } catch (RecognitionException e) {
                return FormValidation.error(e.getMessage());
            }
        	return FormValidation.ok();
        }
        
        /**
         * Checks if the timeout submitted is an integer greater than 0
         * 
         */
        public FormValidation doCheckDefaultTimeout(@QueryParameter String value) {
        	if (!StringUtils.isEmpty(value)) {
        		int timeout;
        		try {
	            	timeout = Integer.parseInt(value);
	            } catch (NumberFormatException e) {
	                return FormValidation.error("Default Timeout should be a number (in seconds).");
	            }
	            
	            if(timeout<=0)
	            	return FormValidation.error("Default Timeout should be greater than 0 (in seconds).");
	            if(maxTimeout>0 && timeout>maxTimeout)
	            	return FormValidation.error("Default Timeout cannot be greater than "+maxTimeout+" seconds (Maximum Timeout).");
        	} else {
        		 if(maxTimeout>0)
 	            	return FormValidation.ok("Default timeout will be set to +" +maxTimeout+" seconds if no value is added. (using Maximum Timeout)");
        		 else
        			 return FormValidation.ok("Default timeout will be set to Maximum Timeout if no value is added.");
        	}
            return FormValidation.ok();
        }
        
        /**
         * Checks if the timeout submitted is an integer greater than 0
         * 
         */
        public FormValidation doCheckTimeout(@QueryParameter String value) {
        	if (!StringUtils.isEmpty(value)) {
        		int timeout;
        		try {
	            	timeout = Integer.parseInt(value);
	            } catch (NumberFormatException e) {
	                return FormValidation.error("Timeout should be a number (in seconds).");
	            }
	            
	            if(timeout<=0)
	            	return FormValidation.error("Timeout should be greater than 0 (in seconds).");
	            if(maxTimeout>0 && timeout>maxTimeout)
	            	return FormValidation.error("Timeout cannot be greater than "+maxTimeout+" seconds (Maximum Timeout is set in the Global Configuration).");
        	} else {
        		if(defaultTimeout==0) 
        			return FormValidation.error("Timeout needs to be specified. Global configuration is missing.");
        		else
        			return FormValidation.ok("Timeout will be set to "+defaultTimeout+" seconds if no value is added (Default Timeout defined in Global Configuration).");
        	}
            return FormValidation.ok();
        }
        
        public FormValidation doCheckMaxTimeout(@QueryParameter String value) {
        	if (!StringUtils.isEmpty(value)) {
        		int timeout;
        		try {
	            	timeout = Integer.parseInt(value);
	            } catch (NumberFormatException e) {
	                return FormValidation.error("Maximum Timeout should be a number (in seconds).");
	            }
	            
	            if(timeout<=0)
	            	return FormValidation.error("Maximum Timeout should be greater than 0 (in seconds).");
	        } else {
	        	if(defaultTimeout>0)
	        		return FormValidation.ok("Maximum Timeout will be set to "+defaultTimeout+" seconds if no value is added.");
	        	else
	        		return FormValidation.ok("Maximum Timeout will be set to Default Timeout if no value is added.");
	        }
            return FormValidation.ok();
        }
        
        private int getTimeout(JSONObject formData) {
        	String timeout = formData.getString("timeout");
        	if (!StringUtils.isEmpty(timeout)) {
        		try {
        			return Integer.parseInt(timeout);
        		} catch (NumberFormatException e) {
        			//In this case use the global timeout
        		}
        	} 
        	return defaultTimeout;
        }
        
        private String getConfSpec(JSONObject formData) throws FormException {
        	String confSpec = formData.getString("confSpec");
        	if (StringUtils.isEmpty(confSpec)) {
        		if(StringUtils.isEmpty(defaultConfSpec)) 
        			throw new FormException("Schedule needs to be specified. Global configuration is missing.", "");
        		else
        			return defaultConfSpec;
        	}
        	return confSpec;
        }
        
        private int getDefaultTimeout(JSONObject formData) {
        	String timeout = formData.getString("defaultTimeout");
        	if (!StringUtils.isEmpty(timeout)) {
        		try {
        			return Integer.parseInt(timeout);
        		} catch (NumberFormatException e) {
        			//This shouldn't happen since we already validated the Timeout
        		}
        	} else {
        		String maxTimeout = formData.getString("maxTimeout");
            	if (!StringUtils.isEmpty(maxTimeout)) {
            		try {
            			return Integer.parseInt(maxTimeout);
            		} catch (NumberFormatException e) {
            			//This shouldn't happen since we already validated the Timeout
            		}
            	}
        	}
        	return 0;
        }
        
        private int getMaxTimeout(JSONObject formData) {
        	String timeout = formData.getString("maxTimeout");
        	if (!StringUtils.isEmpty(timeout)) {
        		try {
        			return Integer.parseInt(timeout);
        		} catch (NumberFormatException e) {
        			//This shouldn't happen since we already validated the Timeout
        		}
        	} else if(defaultTimeout>0) {
        		return defaultTimeout;
        	}
        	return 0;
        }
        
        private String getDefaultConfSpec(JSONObject formData) {
        	String confSpec = formData.getString("defaultConfSpec");
        	if (StringUtils.isEmpty(confSpec)) {
        		if(!StringUtils.isEmpty(minConfSpec))
        			return minConfSpec;
        		else 
        			return "";
        	} else {
        		//Check if the default set if equal or more than the minimum if any
        		if(!StringUtils.isEmpty(minConfSpec) && isLessThanMinConfSpec(confSpec, minConfSpec)) 
        			return minConfSpec;
        	}
        	return confSpec;
        }
        
        private String getMinConfSpec(JSONObject formData) {
        	String minConfSpec = formData.getString("minConfSpec");
        	String confSpec = formData.getString("defaultConfSpec");
        	if (StringUtils.isEmpty(minConfSpec)) {
        		if(!StringUtils.isEmpty(confSpec))
        			return confSpec;
        		else
        			return "";
        	} 
        	return minConfSpec;
        }
        
        public boolean isLessThanMinConfSpec(String expression, String minExpression) {
        	//SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        	
        	try {
    	    	CronTab cron = new CronTab(expression);
    	    	Calendar cal = Calendar.getInstance();
    	    	Calendar nextFireAt = cron.ceil(cal.getTimeInMillis());
    	    	Calendar preFireAt = cron.floor(cal.getTimeInMillis());
    	    	
    	    	CronTab minCron = new CronTab(minExpression);
    	    	Calendar nextMinFireAt = minCron.ceil(cal.getTimeInMillis());
    	    	Calendar preMinFireAt = minCron.floor(cal.getTimeInMillis());
    	    	
    	    	//System.out.println("Current date&time: "+sdf.format(cal.getTime()));
    	    	//System.out.println("Next fire at: "+sdf.format(nextFireAt.getTime()));
    	    	//System.out.println("Previous fire at: "+sdf.format(preFireAt.getTime()));
    	    	//System.out.println("Next Min fire at: "+sdf.format(nextMinFireAt.getTime()));
    	    	//System.out.println("Previous Min fire at: "+sdf.format(preMinFireAt.getTime()));
    	    	
    	    	long minDiff = nextMinFireAt.getTimeInMillis() - preMinFireAt.getTimeInMillis();
    	    	long diff = nextFireAt.getTimeInMillis() - preFireAt.getTimeInMillis();
    	    	if(minDiff>diff)
    	    		return true;
    	    	
        	} catch (RecognitionException e) {}
        	return false;
        }
        
        public int getDefaultTimeout() {
        	return defaultTimeout;
        }
        
        public int getMaxTimeout() {
        	return maxTimeout;
        }
        
        public String getDefaultConfSpec() {
        	return defaultConfSpec;
        }
        
        public String getMinConfSpec() {
        	return minConfSpec;
        }
    }
}
