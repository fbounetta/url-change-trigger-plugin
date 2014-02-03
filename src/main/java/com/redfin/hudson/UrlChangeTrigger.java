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
    		try {
	    		//Get the default confSpec from descriptor if any
	    		if(!StringUtils.isEmpty(getDescriptor().defaultConfSpec)) 
	    			this.tabs = CronTabList.create(getDescriptor().defaultConfSpec); 	
	    		else 
	    			this.tabs = CronTabList.create("* * * * *");
	    	} catch (RecognitionException e) {
		        	throw new RuntimeException("Bug! couldn't schedule poll");
			}
    	}
    	if(timeout==0)
    		timeout = getDescriptor().getDefaultTimeout();
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
        	LOGGER.log(Level.FINE, "*** Job {0} processing URL {1} with {2} seconds timeout.", new Object[] {job.getDisplayName(), url, timeout});
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
 		   if(is!=null) {
 			   try {
 				   is.close();
 			  } catch (IOException e) {
 	 			  throw new RuntimeException(e);
 	 		   }
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
    	long minFrequency;
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
        	validateAndResetTimeouts();
        	minConfSpec = formData.getString("minConfSpec");
        	defaultConfSpec = formData.getString("defaultConfSpec");
        	minFrequency = getMinFrequency(minConfSpec);
        	validateAndResetConfSpecs();
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
	                if(isLessFrequentThanMinConfSpec(value))
	                	return FormValidation.error("Cannot schedule the trigger more frequent than "+minConfSpec +" (Mimimum Schedule set in Global Configuration).");
        		} else {
        			if(StringUtils.isEmpty(defaultConfSpec)) 
            			return FormValidation.error("Schedule needs to be specified. Global configuration is missing.");
            		else
            			return FormValidation.ok("If no Schedule is set for this job, it will use the Default Schedule set in Global Configuration ( "+defaultConfSpec+" ).");
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
        		} else {
        			if(!StringUtils.isEmpty(minConfSpec)) 
        				return FormValidation.ok("Default schedule will be set to "+minConfSpec +" if no value is added (Minimum Schedule).");
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
        				return FormValidation.ok("Minimum schedule will be set to "+defaultConfSpec +" if no value is added (Default Schedule).");
        			else
        				return FormValidation.ok("Minimum schedule will be set to Default Schedule if no value is added.");
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
        	} else {
        		 if(maxTimeout>0)
 	            	return FormValidation.ok("Default Timeout will be set to " +maxTimeout+" seconds if no value is added. (Maximum Timeout)");
        		 else
        			 return FormValidation.ok("Default Timeout will be set to Maximum Timeout if no value is added.");
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
        
        private int getTimeout(JSONObject formData) throws FormException {
        	String time = formData.getString("timeout");
        	int timeout=0;
        	if (!StringUtils.isEmpty(time)) {
        		try {
        			timeout = Integer.parseInt(time);
        		} catch (NumberFormatException e) {
        			//In this case use the global timeout
        		}
        		if(timeout==0) {
        			if(defaultTimeout==0)
        				throw new FormException("Timeout needs to be specified. Global configuration is missing.", "");
        		} else 
        			return timeout;
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
        	} 
        	return 0;
        }
        
        private void validateAndResetTimeouts() throws FormException {
        	if(defaultTimeout>0 && maxTimeout>0) {
        		if(defaultTimeout>maxTimeout) {
        			String msg = "Default Timeout  "+defaultTimeout;
        			defaultTimeout = maxTimeout;
        			throw new FormException(msg+" cannot exceed "+maxTimeout+" seconds (Maximum Timeout).", "");
        		}
        	} else {
        		if(defaultTimeout>0)
        			maxTimeout = defaultTimeout;
        		else
        			defaultTimeout = maxTimeout;
        	}
        }
        
        private void validateAndResetConfSpecs() throws FormException {
        	if(!StringUtils.isEmpty(defaultConfSpec) && !StringUtils.isEmpty(minConfSpec)) {
        		try {
        			if(isLessFrequentThanMinConfSpec(defaultConfSpec)) {
        				String msg = "Default Schedule "+defaultConfSpec;
        				defaultConfSpec = minConfSpec;
        				throw new FormException(msg+" cannot be less than "+minConfSpec+ " (Minimum Schedule).", "");
        			}
        		} catch (RecognitionException e) {
        			throw new FormException(e.getMessage(), e, "");
        		}
        	} else {
        		if(!StringUtils.isEmpty(defaultConfSpec)) {
        			minConfSpec = defaultConfSpec;
        			//recalculate minFrequency
        			minFrequency = getMinFrequency(minConfSpec);
        		} else
        			defaultConfSpec = minConfSpec;
        	}
        }
        
        /**
         * Determine next and previous scheduled time for the given cron expression
         * and store the frequency in a local variable 
         * The returned value will be used to compare new schedule to the minimum allowed 
         */
        private long getMinFrequency(String expression) throws FormException {
        	long min=0;
        	if(!StringUtils.isEmpty(expression)) {
        		try {
	        		long currentTime = Calendar.getInstance().getTimeInMillis();
		        	CronTab minCron = new CronTab(expression);
			    	Calendar nextFireAt = minCron.ceil(currentTime);
			    	Calendar preFireAt = minCron.floor(currentTime);
			    	return nextFireAt.getTimeInMillis() - preFireAt.getTimeInMillis();
        		} catch (RecognitionException e) {
        			throw new FormException("Encountered Error when processing Minimum Schedule ",e, "");
        		}
        	}
        	return min;
        }
        
        /**
        * Determine next and previous scheduled time for the given cron expression
        * Compare it to minFrequency
        * return true if this cron expression will be triggered more frequently than the minimum allowed
        * return false otherwise
        */
        public boolean isLessFrequentThanMinConfSpec(String expression) throws RecognitionException {
        	if(minFrequency>0) {
	        	CronTab cron = new CronTab(expression);
    	    	long currentTime = Calendar.getInstance().getTimeInMillis();
    	    	Calendar nextFireAt = cron.ceil(currentTime);
    	    	Calendar preFireAt = cron.floor(currentTime);
    	    	
    	    	long diff = nextFireAt.getTimeInMillis() - preFireAt.getTimeInMillis();
    	    	if(minFrequency>diff)
    	    		return true;
        	}
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
