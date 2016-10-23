package uk.ac.cam.tfc_server.msgfiler;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// MsgFiler.java
// Version 0.03
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// MsgFiler is dedicated to reading messages from the eventbus and
// storing them in the filesystem.
//
// MsgFiler uses FilerUtils to filter and store the messages.
//
// MsgFiler creates FilerConfig objects to configure the FilerUtils objects as required, e.g.
// to specify the output data path, output filename format and whether to append records to
// an existing file or write new files containing the data.
//
// Is given a list of 'filer' parameters in config() in "msgfiler.filers".  Each entry defines:
//   "source_address": the eventbus address to listen to for messages
//      e.g. "tfc.zone"
//   "source_filter" : a json object that specifies which subset of messages to write to disk
//      e.g. { "field": "msg_type", "compare": "=", "value": "zone_completion" }
//   "store_path" : a parameterized string giving the full filepath for storing the message
//      e.g. "/home/ijl20/tfc_server_data/data_zone/{{ts|yyyy}}/{{ts|MM}}/{{ts|dd}}"
//   "store_name" : a parameterized string giving the filename for storing the message
//      e.g. "{{module_id}}.txt"
//   "store_mode" : "write" | "append", defining whether the given file should be written or appended
//
// Publishes periodic status UP messages to address given in config as "eb.system_status"
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.Future;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import uk.ac.cam.tfc_server.core.AbstractTFCVerticle;
import uk.ac.cam.tfc_server.util.Log;

import java.util.ArrayList;

// other tfc_server classes

public class MsgFiler extends AbstractTFCVerticle {
    // from config()
    private ArrayList<FilerConfig> START_FILERS; // config msgfilers.filers parameters
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
    private final int SYSTEM_STATUS_RED_SECONDS = 25;

    private EventBus eb = null;
    
  @Override
  public void start(Future<Void> fut) throws Exception {
      
    // load initialization values from config()
    if (!get_config())
          {
              Log.log_err("MsgFiler."+ MODULE_ID + ": failed to load initial config()");
              vertx.close();
              return;
          }
      
    System.out.println("MsgFiler." + MODULE_ID + ": started");

    eb = vertx.eventBus();

    // iterate through all the filers to be started
    for (int i=0; i<START_FILERS.size(); i++)
        {
            start_filer(START_FILERS.get(i));
        }

    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
      eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+MODULE_NAME+"\"," +
                   "\"module_id\": \""+MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
    });

  } // end start()

    // ************************************************************
    // start_filer()
    // start a Filer by registering a consumer to the given address
    // ************************************************************
    private void start_filer(FilerConfig filer_config)
    {
        String filer_filter;
        if (filer_config.source_filter == null)
            {
                filer_filter = "";
            }
        else
            {
                filer_filter = " with " + filer_config.source_filter.toString();
            }
        System.out.println("MsgFiler."+MODULE_ID+": starting filer "+filer_config.source_address+ filer_filter);

        FilerUtils filer_utils = new FilerUtils(vertx, filer_config);
        
        // register to filer_config.source_address,
        // test messages with filer_config.source_filter
        // and call store_msg if current message passes filter
        eb.consumer(filer_config.source_address, message -> {
            //System.out.println("MsgFiler."+MODULE_ID+": got message from " + filer_config.source_address);
            JsonObject msg = new JsonObject(message.body().toString());
            
            //System.out.println(msg.toString());

            // store this message if it matches the filter within the FilerConfig
            filer_utils.store_msg(msg);

        });
    
    } // end start_filer

    
    //**************************************************************************
    //**************************************************************************
    // Load initialization global constants defining this MsgFiler from config()
    //**************************************************************************
    //**************************************************************************
    protected boolean get_config()
    {
        boolean results = super.get_config();
        if (!results) return false;

        // iterate through the msgfiler.filers config values
        START_FILERS = new ArrayList<FilerConfig>();
        JsonArray config_filer_list = config().getJsonArray(MODULE_NAME+".filers");
        for (int i=0; i<config_filer_list.size(); i++)
            {
                JsonObject config_json = config_filer_list.getJsonObject(i);

                // add MODULE_NAME, MODULE_ID to every FilerConfig
                config_json.put("module_name", MODULE_NAME);
                config_json.put("module_id", MODULE_ID);
                
                FilerConfig filer_config = new FilerConfig(config_json);
                
                START_FILERS.add(filer_config);
            }

        return true;
    }

}
