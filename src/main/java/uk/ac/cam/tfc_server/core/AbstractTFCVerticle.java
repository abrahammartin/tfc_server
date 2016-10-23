package uk.ac.cam.tfc_server.core;

import io.vertx.core.AbstractVerticle;
import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;


public abstract class AbstractTFCVerticle extends AbstractVerticle {
    // Config vars
    protected String MODULE_NAME;
    protected String MODULE_ID;
    protected String EB_SYSTEM_STATUS; // eventbus status reporting address
    protected String EB_MANAGER; // eventbus status reporting address

    /**
     * Load initialization global constants from config()
     * <p>
     * This can be specified when the verticle is deployed.
     * @return if it was successful
     */
    protected boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   module.name - name of the module
        //   module.id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages
        //   eb.manager - evenbus address to subscribe to for system management messages

        MODULE_NAME = config().getString("module.name");
        if (MODULE_NAME == null) {
            System.err.println(getClass().toString() + ": module.name config() not set");
            return false;
        }

        MODULE_ID = config().getString("module.id");
        if (MODULE_ID == null) {
            System.err.println(MODULE_NAME+": module.id config() not set");
            return false;
        }

        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS == null) {
            System.err.println(MODULE_NAME+"."+MODULE_ID+": eb.system_status config() not set");
            return false;
        }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER==null)
        {
            System.err.println(MODULE_NAME+"."+MODULE_ID+": eb.manager config() not set");
            return false;
        }

        return true;
    }
}
