package uk.ac.cam.tfc_server.staticserver;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// StaticServer.java
//
// StaticServer is a basic http server for service static data from webroot/static
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.DeploymentOptions;

import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpMethod;

import io.vertx.core.eventbus.EventBus;

// vertx web, service proxy, sockjs eventbus bridge
import io.vertx.ext.web.Router;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.StaticHandler;
import uk.ac.cam.tfc_server.core.AbstractTFCVerticle;

public class StaticServer extends AbstractTFCVerticle {

    private Integer HTTP_PORT; // from config()

    private String WEBROOT; // from config()
    
    private String BASE_URI; // used as template parameter for web pages, built from config()
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
    private final int SYSTEM_STATUS_RED_SECONDS = 25;

    // Vertx event bus
    private EventBus eb = null;

    @Override
    public void start(Future<Void> fut) throws Exception
    {

    // Get src/main/conf/tfc_server.conf config values for module
    if (!get_config())
        {
            System.err.println("StaticServer: problem loading config");
            vertx.close();
            return;
        }

    System.out.println("StaticServer starting as "+MODULE_NAME+"."+MODULE_ID+
                       " on port "+HTTP_PORT );

    BASE_URI = MODULE_NAME; // typically 'staticserver'

    eb = vertx.eventBus();

    // send periodic "system_status" messages
    init_system_status();
    
    // *************************************************************************************
    // *************************************************************************************
    // *********** Start StaticServer web server                                ************
    // *************************************************************************************
    // *************************************************************************************
    HttpServer http_server = vertx.createHttpServer();

    Router router = Router.router(vertx);


    // ********************************
    // create handler for embedded page
    // ********************************

    router.route("/static/"+BASE_URI+"/home").handler( routingContext -> {

        HttpServerResponse response = routingContext.response();
        response.putHeader("content-type", "text/html");

        response.end("<h1>Rita::StaticServer."+MODULE_ID+"</h1><p>Vertx-Web!</p>");
    });


    // ********************************
    // create handler for static pages
    // ********************************

    StaticHandler static_handler = StaticHandler.create();
    static_handler.setWebRoot(WEBROOT);
    static_handler.setCachingEnabled(false);
    router.route(HttpMethod.GET, "/static/*").handler( static_handler );

    System.out.println("StaticHandler."+MODULE_ID+" using "+WEBROOT);
    
    // ********************************
    // connect router to http_server
    // ********************************

    http_server.requestHandler(router::accept).listen(HTTP_PORT);

  } // end start()

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
    
    // Load initialization global constants defining this module from config()
    protected boolean get_config()
    {
        boolean results = super.get_config();
        if (!results) return false;

        // port for user browser access to this Rita
        HTTP_PORT = config().getInteger(MODULE_NAME+".http.port");
        if (HTTP_PORT==null)
            {
                System.err.println("StaticServer: no "+MODULE_NAME+".http.port in config()");
                return false;
            }

        // where the built-in webserver will find static files
        WEBROOT = config().getString(MODULE_NAME+".webroot");
        if (WEBROOT==null)
            {
                System.err.println("StaticServer: no "+MODULE_NAME+".webroot in config()");
                return false;
            }

        return true;
    }

} // end class Rita


