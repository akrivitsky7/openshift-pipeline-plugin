package com.openshift.openshiftjenkinsbuildutils;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import net.sf.json.JSONObject;

import org.jboss.dmr.ModelNode;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import com.openshift.internal.restclient.http.HttpClientException;
import com.openshift.internal.restclient.http.UrlConnectionHttpClient;
import com.openshift.internal.restclient.model.Build;
import com.openshift.internal.restclient.model.build.BuildRequest;
import com.openshift.restclient.ClientFactory;
import com.openshift.restclient.IClient;
import com.openshift.restclient.ISSLCertificateCallback;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.authorization.TokenAuthorizationStrategy;
import com.openshift.restclient.capability.CapabilityVisitor;
import com.openshift.restclient.capability.ICapability;
import com.openshift.restclient.capability.resources.IBuildTriggerable;
import com.openshift.restclient.capability.resources.IPodLogRetrieval;
import com.openshift.restclient.model.IBuild;
import com.openshift.restclient.model.IBuildConfig;
import com.openshift.restclient.model.IPod;
import com.openshift.restclient.model.build.IBuildRequest;

import javax.net.ssl.SSLSession;
import javax.servlet.ServletException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * OpenShift {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link OpenShiftBuildCanceller} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #name})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform(AbstractBuild, Launcher, BuildListener)}
 * method will be invoked. 
 *
 * @author Gabe Montero
 */
public class OpenShiftBuildCanceller extends Recorder implements ISSLCertificateCallback {
	
    private String apiURL = "https://openshift.default.svc.cluster.local";
    private String namespace = "test";
    private String authToken = "";
    private String verbose = "false";
    private String bldCfg ="frontend";
    
    
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public OpenShiftBuildCanceller(String apiURL, String namespace, String authToken, String verbose, String buildConfig) {
        this.apiURL = apiURL;
        this.namespace = namespace;
        this.authToken = authToken;
        this.verbose = verbose;
        this.bldCfg = buildConfig;
    }

    /**
     * We'll use this from the <tt>config.jelly</tt>.
     */
    public String getApiURL() {
		return apiURL;
	}

	public String getNamespace() {
		return namespace;
	}
	
	public String getAuthToken() {
		return authToken;
	}
	
    public String getVerbose() {
		return verbose;
	}

	public void setVerbose(String verbose) {
		this.verbose = verbose;
	}

    public void setApiURL(String apiURL) {
		this.apiURL = apiURL;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public void setAuthToken(String authToken) {
		this.authToken = authToken;
	}


	public String getBldCfg() {
		return bldCfg;
	}

	public void setBldCfg(String buildConfig) {
		this.bldCfg = buildConfig;
	}

	// Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    
    
	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@Override
	public boolean needsToRunAfterFinalized() {
		return true;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		boolean chatty = Boolean.parseBoolean(verbose);
		Result result = build.getResult();
		
		// in theory, success should mean that the builds completed successfully,
		// at this time, we'll scan the builds either way to clean up rogue builds
		if (result.isWorseThan(Result.SUCCESS)) {
			if (chatty)
				listener.getLogger().println("\nOpenShiftBuildCanceller build did not succeed");
		} else {
			if (chatty)
				listener.getLogger().println("\nOpenShiftBuildCanceller build succeeded");			
		}

		authToken = Auth.deriveAuth(authToken, listener, chatty);
		
    	// get oc client (sometime REST, sometimes Exec of oc command
    	IClient client = new ClientFactory().create(apiURL, this);
    	
    	if (client != null) {
    		// seed the auth
        	client.setAuthorizationStrategy(new TokenAuthorizationStrategy(this.authToken));
			
			// create stream and copy bytes
	    	URL url = null;
	    	try {
				url = new URL(apiURL + "/oapi/v1/namespaces/"+namespace+"/builds");
			} catch (MalformedURLException e1) {
				e1.printStackTrace(listener.getLogger());
				return false;
			}
			UrlConnectionHttpClient urlClient = new UrlConnectionHttpClient(
					null, "application/json", null, this, null, null);
			urlClient.setAuthorizationStrategy(new TokenAuthorizationStrategy(authToken));
			String response = null;
			try {
				response = urlClient.get(url, 2 * 60 * 1000);
				ModelNode responseJson = ModelNode.fromJSONString(response);
				if (chatty)
					listener.getLogger().println("\nOpenShiftBuildCanceller response " + responseJson.asString());
				ModelNode list = responseJson.get("items");
				if (chatty)
					listener.getLogger().println("\nOpenShiftBuildCanceller list " + list.asString());
				int i=0;
				while (list.has(i)) {
					ModelNode buildNode = list.get(i);
					if (chatty) 
						listener.getLogger().println("\nOpenShiftBuildCanceller build node " + buildNode.asString());
					ModelNode statusNode = buildNode.get("status");
					ModelNode phase = statusNode.get("phase");
					String phaseStr = phase.asString();
					
					// if build active, let's cancel it
					if (!phaseStr.equalsIgnoreCase("Complete") && !phaseStr.equalsIgnoreCase("Failed") && !phaseStr.equalsIgnoreCase("Cancelled")) {
						ModelNode metadata = buildNode.get("metadata");
						if (chatty)
							listener.getLogger().println("\nOpenShiftBuildCanceller metadata " + metadata.asString());
						ModelNode name = metadata.get("name");
						String buildName = name.asString();
						if (chatty)
							listener.getLogger().println("\nOpenShiftBuildCanceller name " + buildName);
						if (chatty)
							listener.getLogger().println("\nOpenShiftBuildCanceller found active build " + buildName);
						
						// reget - optimistic update, need IResource
						Build bld = client.get(ResourceKind.BUILD, buildName, namespace);
						ModelNode bldJson = bld.getNode();
						if (chatty)
							listener.getLogger().println("\nOpenShiftBuildCanceller bld state:  " + bldJson.asString());
						ModelNode status = bldJson.get("status");
						if (chatty) 
							listener.getLogger().println("\nOpenShiftBuildCanceller status pre " + status.asString());
						status.get("cancelled").set(true);
						if (chatty) 
							listener.getLogger().println("\nOpenShiftBuildCanceller status post " + status.asString());
				    	
				    	try {
							url = new URL(apiURL + "/oapi/v1/namespaces/"+namespace+"/builds/" + buildName);
						} catch (MalformedURLException e1) {
							e1.printStackTrace(listener.getLogger());
							return false;
						}
						urlClient = new UrlConnectionHttpClient(
								null, "application/json", null, this, null, null);
						urlClient.setAuthorizationStrategy(new TokenAuthorizationStrategy(authToken));
						try {
							response = urlClient.put(url, 2 * 60 * 1000, bld);
							if (chatty)
								listener.getLogger().println("\nOpenShiftBuildCanceller response " + response);
						} catch (SocketTimeoutException e1) {
							e1.printStackTrace(listener.getLogger());
							return false;
						} catch (HttpClientException e1) {
							e1.printStackTrace(listener.getLogger());
							return false;
						}
						
					}
					i++;
				}
			} catch (SocketTimeoutException e1) {
				e1.printStackTrace(listener.getLogger());
				return false;
			} catch (HttpClientException e1) {
				e1.printStackTrace(listener.getLogger());
				return false;
			}
    	}			
		return true;
	}



	/**
     * Descriptor for {@link OpenShiftBuildCanceller}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the various fields.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckApiURL(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set apiURL");
            return FormValidation.ok();
        }

        public FormValidation doCheckBldCfg(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set bldCfg");
            return FormValidation.ok();
        }

        public FormValidation doCheckNamespace(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set namespace");
            return FormValidation.ok();
        }
        
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Cancel builds in OpenShift";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // pull info from formData, set appropriate instance field (which should have a getter), and call save().
            save();
            return super.configure(req,formData);
        }

    }

	@Override
	public boolean allowCertificate(X509Certificate[] chain) {
		return true;
	}

	@Override
	public boolean allowHostname(String hostname, SSLSession session) {
		return true;
	}

}
