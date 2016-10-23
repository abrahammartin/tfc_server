package uk.ac.cam.tfc_server.rita;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// Rita.java
//
// RITA is the user "master controller" of the tfc modules...
// Based on user requests via the http UI, RITA will spawn feedplayers and zones, to
// display the analysis in real time on the user browser.
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Provides an HTTP server that serves the end user
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.*;
import io.vertx.ext.web.templ.HandlebarsTemplateEngine;
import uk.ac.cam.tfc_server.core.AbstractTFCVerticle;
import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Set;

// vertx web, service proxy, sockjs eventbus bridge
// handlebars for static .hbs web template files
// other tfc_server classes

public class Rita extends AbstractTFCVerticle {

    // values from config()
    private Integer HTTP_PORT;
    private String RITA_ADDRESS;
    private String WEBROOT;
    private int    LOG_LEVEL;
    
    //debug - feedplayers and zonemanagers may come from user commands
    private ArrayList<String> FEEDPLAYERS; // optional from config()
    private ArrayList<String> ZONEMANAGERS; // optional from config()

    // default MODULE_NAME values for deployed verticles
    private final String FEEDPLAYER_NAME = "feedplayer";
    private final String ZONEMANAGER_NAME = "zonemanager";

    private String ZONE_ADDRESS; // optional from config()
    private String ZONE_FEED; // optional from config()
    private String FEEDPLAYER_ADDRESS; // optional from config()

    private String BASE_URI; // used as template parameter for web pages, built from config()
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
    private final int SYSTEM_STATUS_RED_SECONDS = 25;

    // Log
    private Log logger;
   
    // Vertx event bus
    private EventBus eb = null;

    // data structure to hold data subscription info of each connected user
    private ClientTable client_table;
    
    @Override
    public void start(Future<Void> fut) throws Exception
    {

    // Get src/main/conf/tfc_server.conf config values for module
    if (!get_config())
        {
            System.err.println("Rita: problem loading config");
            vertx.close();
            return;
        }

    logger = new Log(LOG_LEVEL);
        
    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+": config()=");
    logger.log(Constants.LOG_DEBUG, config().toString());
        
    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": starting on port "+HTTP_PORT );

    BASE_URI = MODULE_NAME; // typically 'rita'

    // initialize object to hold socket connection data for each connected session
    client_table = new ClientTable();
    
    eb = vertx.eventBus();

    //debug also need to spawn modules based on messages from client browser
    // get test config options for FeedPlayers from conf file given to Rita
    for (int i=0; i<FEEDPLAYERS.size(); i++)
        {
            deploy_feed_player(FEEDPLAYERS.get(i));
        }
    
    //debug currently only spawning zonemanagers at startup - should support browser client messages
    for (int i=0; i<ZONEMANAGERS.size(); i++)
        {
            deploy_zone_manager(ZONEMANAGERS.get(i));
        }

    // send periodic "system_status" messages
    init_system_status();
    
    // *************************************************************************************
    // *************************************************************************************
    // *********** Start Rita web server (incl Socket and EventBus Bridge)      ************
    // *************************************************************************************
    // *************************************************************************************
    HttpServer http_server = vertx.createHttpServer();

    Router router = Router.router(vertx);

    // *********************************
    // create handler for browser socket 
    // *********************************

    SockJSHandlerOptions sock_options = new SockJSHandlerOptions().setHeartbeatInterval(2000);

    SockJSHandler sock_handler = SockJSHandler.create(vertx, sock_options);

    sock_handler.socketHandler( sock -> {
            // Rita received new socket connection
            logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": sock connection received with "+sock.writeHandlerID());
            
            // Assign a handler funtion to receive data if send
            sock.handler( buf -> {
               logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": sock received '"+buf+"'");

               JsonObject sock_msg = new JsonObject(buf.toString());

               if (sock_msg.getString("msg_type").equals(Constants.SOCKET_ZONE_CONNECT))
               {
                   // Add this connection to the client table
                   // and set up consumer for eventbud messages
                   create_zone_subscription(sock, sock_msg);
               }
               else if (sock_msg.getString("msg_type").equals(Constants.SOCKET_ZONE_MAP_CONNECT))
               {
                   // Add this connection to the client table
                   // and set up consumer for eventbud messages
                   create_zone_map_subscription(sock, sock_msg);
               }
            });

            sock.endHandler( (Void v) -> {
                    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": sock closed "+sock.writeHandlerID());
                });
      });

    router.route("/"+BASE_URI+"/ws/*").handler(sock_handler);

    // ********************************
    // create handler for embedded page
    // ********************************

    router.route("/"+BASE_URI+"/home").handler( routingContext -> {

        HttpServerResponse response = routingContext.response();
        response.putHeader("content-type", "text/html");

        response.end("<h1>TFC Rita</h1><p>Vertx-Web!</p>");
    });

    // **********************************
    // create handler for eventbus bridge
    // **********************************

    SockJSHandler ebHandler = SockJSHandler.create(vertx);

    BridgeOptions bridge_options = new BridgeOptions();
    // add outbound address for user messages
    bridge_options.addOutboundPermitted( new PermittedOptions().setAddress("rita_out") );
    // add outbound address for feed messages
    if (ZONE_FEED != null)
    {
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": permitting eventbus "+ZONE_FEED+" to browser");
        bridge_options.addOutboundPermitted( new PermittedOptions().setAddress(ZONE_FEED) );
    }

    ebHandler.bridge(bridge_options);

    router.route("/"+BASE_URI+"/eb/*").handler(ebHandler);

    // ***********************************
    // create handler for zone restful api
    // ***********************************

    router.route(HttpMethod.GET, "/"+BASE_URI+"/api/zone/:zoneid").handler( routingContext -> {
            String zone_id = routingContext.request().getParam("zoneid");

            if (zone_id == null) {
                routingContext.response().setStatusCode(400).end();
            } else {
                routingContext.response()
                    //debug restful api just a stub
                       .putHeader("content-type", "text/html")
                       .end("<h1>Zone "+zone_id+"</h1>");
            }
            routingContext.response().setStatusCode(200).end();
        } );

    // **************************************
    // **************************************
    // create handlers for template pages
    // **************************************
    // **************************************

    final HandlebarsTemplateEngine template_engine = HandlebarsTemplateEngine.create();
    
    router.route(HttpMethod.GET,"/"+BASE_URI+"/zone/:zoneid/plot").handler( ctx -> {
            
            String zone_id = ctx.request().getParam("zoneid");

            logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": serving zone_plot.hbs for "+zone_id);
            
            ctx.put("config_zone_id",zone_id); // pass zone_id from URL into template var
            ctx.put("config_UUID", get_UUID());// add template var for Unique User ID
            ctx.put("config_base_uri", BASE_URI);// add template var for base URI e.g. 'rita'
            
            if (zone_id == null)
            {
                ctx.response().setStatusCode(400).end();
            }
            else
            {
                template_engine.render(ctx, "templates/zone_plot.hbs", res -> {
                        if (res.succeeded())
                        {
                            ctx.response().end(res.result());
                        }
                        else
                        {
                            ctx.fail(res.cause());
                        }
                    });
            }
        } );

    router.route(HttpMethod.GET, "/"+BASE_URI+"/zone/:zoneid/map").handler( ctx -> {
            serve_zone_map(ctx, ctx.request().getParam("zoneid"), template_engine);
        });
            
    router.route(HttpMethod.GET, "/"+BASE_URI+"/feed").handler( ctx -> {

            if (ZONE_FEED == null)
                {
                  ctx.response().setStatusCode(400).end();
                }
            else
                {
                    ctx.put("config_feed_address",ZONE_FEED); // pass zone_id from URL into template var

                    template_engine.render(ctx, "templates/feed.hbs", res -> {
                            if (res.succeeded())
                            {
                                ctx.response().end(res.result());
                            }
                            else
                            {
                                ctx.fail(res.cause());
                            }
                        });
                }
        } );

    // ****************************************
    // create handler for constants.js template
    // ****************************************

    router.route(HttpMethod.GET, "/"+BASE_URI+"/constants.js").handler( ctx -> {

            ctx.put("config_constants", Constants.js());  // get constants in JS format
            
            template_engine.render(ctx, "templates/constants.js.hbs", res -> {
                    if (res.succeeded())
                    {
                        ctx.response().end(res.result());
                    }
                    else
                    {
                        ctx.fail(res.cause());
                    }
                });
        } );

    // ********************************
    // create handler for static pages
    // ********************************

    StaticHandler static_handler = StaticHandler.create();
    static_handler.setWebRoot(WEBROOT);
    static_handler.setCachingEnabled(false);
    router.route(HttpMethod.GET, "/static/*").handler( static_handler );

    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+" static handler using "+WEBROOT);
    
    // ********************************
    // connect router to http_server
    // ********************************

    http_server.requestHandler(router::accept).listen(HTTP_PORT);

  } // end start()

    // *******************************************************************************
    // *******************************************************************************
    // *******************************************************************************
    
    // Deploy FeedPlayer verticle
    private void deploy_feed_player(String feedplayer_id)
    {
        DeploymentOptions feedplayer_options = new DeploymentOptions();
        JsonObject conf = new JsonObject();

        conf.put("module.name", FEEDPLAYER_NAME);
        conf.put("module.id", feedplayer_id);
        conf.put(FEEDPLAYER_NAME+".log_level", LOG_LEVEL);
        conf.put("eb.system_status", EB_SYSTEM_STATUS);
        conf.put("eb.manager", EB_MANAGER);
        conf.put(FEEDPLAYER_NAME+".address", FEEDPLAYER_ADDRESS);

        feedplayer_options.setConfig(conf);
        
        vertx.deployVerticle("service:uk.ac.cam.tfc_server.feedplayer."+feedplayer_id,
                             feedplayer_options,
                             res -> {
                if (res.succeeded()) {
                    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                               ": FeedPlayer "+FEEDPLAYER_NAME+"."+feedplayer_id+ " started");
                } else {
                    System.err.println(MODULE_NAME+"."+MODULE_ID+
                                       ": failed to start FeedPlayer " + feedplayer_id);
                    //fut.fail(res.cause());
                }
            });
    }
    
    // Deploy ZoneManager verticle
    private void deploy_zone_manager(String zonemanager_id)
    {
        DeploymentOptions zonemanager_options = new DeploymentOptions();
        JsonObject conf = new JsonObject();
        conf.put("module.name", ZONEMANAGER_NAME);
        conf.put("module.id", zonemanager_id);
        conf.put(ZONEMANAGER_NAME+".log_level", LOG_LEVEL);
        conf.put("eb.system_status", EB_SYSTEM_STATUS);
        conf.put("eb.manager", EB_MANAGER);
        conf.put(ZONEMANAGER_NAME+".zone.address", ZONE_ADDRESS);
        conf.put(ZONEMANAGER_NAME+".zone.feed", ZONE_FEED);

        zonemanager_options.setConfig(conf);
        
        vertx.deployVerticle("service:uk.ac.cam.tfc_server.zonemanager."+zonemanager_id,
                             zonemanager_options,
                             res -> {
                if (res.succeeded()) {
                    logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                               ": ZoneManager "+ZONEMANAGER_NAME+"."+zonemanager_id+" started");
                } else {
                    System.err.println(MODULE_NAME+"."+MODULE_ID+
                                       ": failed to start ZoneManager " + zonemanager_id);
                    //fut.fail(res.cause());
                }
            });
    }

    // Set periodic timer to broadcast "system UP" status messages to EB_SYSTEM_STATUS address
    private void init_system_status()
    {
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
      eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+MODULE_NAME+"\"," +
                   "\"module_id\": \""+MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
      });
    }

    // zone_plot.hbs: create new client connection
    // on receipt of 'zone_connect' message on socket
  private void create_zone_subscription(SockJSSocket sock, JsonObject sock_msg)
    {
        // create entry in client table
        String UUID = client_table.add(sock, sock_msg);

        ArrayList<String> zone_ids = client_table.get(UUID).zone_ids;

        //debug currently assuming webpage only subscribes to a single zone
        String zone_id = zone_ids.get(0);
        
        // register consumer of relevant eventbus messages

        String this_zone_address = ZONE_ADDRESS+"."+zone_id;
        
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": subscribing client to "+this_zone_address);
        
        eb.consumer(this_zone_address, message -> {
                send_client(sock, message.body().toString());
                });

        // also send ZONE_UPDATE_REQUEST to catch up earlier messages
        JsonObject msg = new JsonObject();

        msg.put("module_name", MODULE_NAME); // module name (i.e. 'rita') sending this request
        msg.put("module_id", MODULE_ID); // module id sending this request
        msg.put("to_module_name", "zone"); // module name / module id intended to action this management request
        msg.put("to_module_id", zone_id);
        msg.put("zone.address", this_zone_address);
        msg.put("msg_type", Constants.ZONE_UPDATE_REQUEST);

        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": sending EB_MANAGER msg "+msg.toString());
        
        // request a ZONE_UPDATE from relevant zone
        eb.publish(EB_MANAGER, msg);
    }

    // zone_map.hbs: create new client connection
    // on receipt of 'zone_map_connect' message on socket
  private void create_zone_map_subscription(SockJSSocket sock, JsonObject sock_msg)
    {
        // create entry in client table
        String UUID = client_table.add(sock, sock_msg);

        ArrayList<String> zone_ids = client_table.get(UUID).zone_ids;

        //debug currently assuming webpage only subscribes to a single zone
        String zone_id = zone_ids.get(0);
        
        // register consumer of relevant eventbus messages

        String this_zone_address = ZONE_ADDRESS+"."+zone_id;
        
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": subscribing client to "+this_zone_address);
        
        eb.consumer(this_zone_address, message -> {
                send_client(sock, message.body().toString());
                });

        // also send ZONE_INFO_REQUEST to get Zone details
        JsonObject msg = new JsonObject();

        msg.put("module_name", MODULE_NAME); // module name (i.e. 'rita') sending this request
        msg.put("module_id", MODULE_ID); // module id sending this request
        msg.put("to_module_name", "zone"); // module name / module id intended to action this management request
        msg.put("to_module_id", zone_id);
        msg.put("zone.address", this_zone_address);
        msg.put("msg_type", Constants.ZONE_INFO_REQUEST);

        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": sending EB_MANAGER msg "+msg.toString());
        
        // request a ZONE_INFO from relevant zone
        eb.publish(EB_MANAGER, msg);
    }

    // Serve the templates/zone_map.hbs web page
    private void serve_zone_map(RoutingContext ctx, String zone_id, HandlebarsTemplateEngine engine)
    {
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": serving zone_map.hbs for "+zone_id);
            
        ctx.put("config_zone_id",zone_id); // pass zone_id from URL into template var
        ctx.put("config_UUID", get_UUID());// add template var for Unique User ID
        ctx.put("config_base_uri", BASE_URI);// add template var for base URI e.g. 'rita'

        if (zone_id == null)
        {
            ctx.response().setStatusCode(400).end();
        }
        else
        {
            engine.render(ctx, "templates/zone_map.hbs", res -> {
                    if (res.succeeded())
                    {
                        ctx.response().end(res.result());
                    }
                    else
                    {
                        ctx.fail(res.cause());
                    }
                });
        }
    }
    
    private void send_client(SockJSSocket sock, String msg)
    {
        sock.write(Buffer.buffer(msg));
    }

    // For a given Zone completion message
    // if the zone matches the zone_id in a user subscription
    // then forward the message on that socket
    private void send_user_messages(String msg)
    {
        //debug we're using a single hardcoded socket ref
        //debug will need to iterate through sockets in sock_info
        //debug not yet handling socket close

        JsonObject msg_jo = new JsonObject(msg);
        String msg_zone_id= msg_jo.getString("module_id");
                
        // for each client socket entry in sock_info
        //   if sock_data is not null
        //     for each zone_id in subscription on that socket
        //       if zone_id == zone_id in Zone msg
        //         then forward the message on this socket
        for (String UUID: client_table.keys())
            {
                ClientConfig client_config = client_table.get(UUID);
                
                if (client_config != null)
                    {
                        for (String zone_id: client_config.zone_ids)
                            {
                                if (zone_id.equals(msg_zone_id))
                                    {
                                        client_config.sock.write(Buffer.buffer(msg));
                                    }
                            }
                    }
            }
    }
    
    // Load initialization global constants defining this module from config()
    protected boolean get_config()
    {
        boolean results = super.get_config();
        if (!results) return false;

        LOG_LEVEL = config().getInteger(MODULE_NAME+".log_level", 0);
        if (LOG_LEVEL==0)
            {
                LOG_LEVEL = Constants.LOG_INFO;
            }

        // eventbus address for this Rita to publish its messages to
        RITA_ADDRESS = config().getString(MODULE_NAME+".address");

        // port for user browser access to this Rita
        HTTP_PORT = config().getInteger(MODULE_NAME+".http.port");
        if (HTTP_PORT==null)
            {
                System.err.println("Rita: no "+MODULE_NAME+".http.port in config()");
                return false;
            }

        // where the built-in webserver will find static files
        WEBROOT = config().getString(MODULE_NAME+".webroot");
        //debug we should properly test for bad config

        // get list of FeedPlayers to start on startup
        FEEDPLAYERS = new ArrayList<String>();
        JsonArray feedplayer_list = config().getJsonArray(MODULE_NAME+".feedplayers");
        if (feedplayer_list != null)
            {
                for (int i=0; i<feedplayer_list.size(); i++)
                    {
                        FEEDPLAYERS.add(feedplayer_list.getString(i));
                    }
            }

        // get list of ZoneManager id's to start on startup
        ZONEMANAGERS = new ArrayList<String>();
        JsonArray zonemanager_list = config().getJsonArray(MODULE_NAME+".zonemanagers");
        if (zonemanager_list != null)
            {
                for (int i=0; i<zonemanager_list.size(); i++)
                    {
                        ZONEMANAGERS.add(zonemanager_list.getString(i));
                    }
            }

        // the eventbus address for the Zones to publish their messages to
        ZONE_ADDRESS = config().getString(MODULE_NAME+".zone.address");

        // the eventbus address for the Zones to subscribe to
        ZONE_FEED = config().getString(MODULE_NAME+".zone.feed");

        // note if we start FeedPlayers, ZONE_FEED will typically be FEEDPLAYER_ADDRESS
        FEEDPLAYER_ADDRESS = config().getString(MODULE_NAME+".feedplayer.address");
        
        return true;
    }

    // generate a new Unique User ID for each socket connection
    private String get_UUID()
    {
        return String.valueOf(System.currentTimeMillis());
    }

    // Data for each socket connection
    // session_id is in sock.webSession().id()
    class ClientConfig {
        public String UUID;         // unique ID for this connection
        public SockJSSocket sock;   // actual socket reference
        public ArrayList<String> zone_ids; // zone_ids relevant to this client connection
    }

    // Object to store data for all current socket connections
    class ClientTable {

        private Hashtable<String,ClientConfig> client_table;

        // initialize new SockInfo object
        ClientTable () {
            client_table = new Hashtable<String,ClientConfig>();
        }

        // Add new connection to known list, with zone_ids in buf
        // returns UUID of entry added
        public String add(SockJSSocket sock, JsonObject sock_msg)
        {
            if (sock == null)
                {
                    System.err.println("Rita."+MODULE_ID+": ClientTable.add() called with sock==null");
                    return null;
                }

            // create new entry for sock_data
            ClientConfig entry = new ClientConfig();
            entry.sock = sock;

            entry.zone_ids = new ArrayList<String>();

            JsonArray zones_ja = sock_msg.getJsonArray("zone_ids");
            for (int i=0; i<zones_ja.size(); i++)
                {
                    entry.zone_ids.add(zones_ja.getString(i));
                }
            logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                       ": ClientTable.add "+sock_msg.getString("UUID")+ " " +entry.zone_ids.toString());
            // push this entry onto the array
            String UUID = sock_msg.getString("UUID");        
            client_table.put(UUID,entry);
            return UUID;
        }

        public ClientConfig get(String UUID)
        {
            // retrieve data for current socket, if it exists
            ClientConfig client_config = client_table.get(UUID);

            if (client_config != null)
                {
                    return client_config;
                }
            System.err.println("Rita."+MODULE_ID+": ClientTable.get '"+UUID+"' entry not found in client_table");
            return null;
        }

        public void remove(String UUID)
        {
            ClientConfig client_config = client_table.remove(UUID);
            if (client_config == null)
                {
                    System.err.println("Rita."+MODULE_ID+": ClientTable.remove non-existent session_id "+UUID);
                }
        }

        public Set<String> keys()
        {
            return client_table.keySet();
        }
    } // end class ClientTable
    
} // end class Rita
