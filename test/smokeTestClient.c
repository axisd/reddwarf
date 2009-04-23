/* 
 * File:   smokeTestClient.c
 * Author: waldo
 *
 * Created on April 16, 2009, 4:54 PM
 */

#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <poll.h>
#include <string.h>
#include <unistd.h>
#include <sys/select.h>
#include <getopt.h>
#include <wchar.h>
#include "sgs/connection.h"
#include "sgs/context.h"
#include "sgs/session.h"
#include "sgs/map.h"
#include "testCallbacks.h"


/*
 * 
 */

/* The default server connections; these can be
 * over-ridden on the command line
 */
#define DEFAULT_HOST "localhost"
#define DEFAULT_PORT 1139

/* Some global variables, declared to make life easier */

static char *g_hostname = DEFAULT_HOST;
static int g_port = DEFAULT_PORT;


void getCommandArgs(int count, char *args[]){
    int c;
    while ((c = getopt(count, args, "h:p:u")) != -1){
        switch (c){
            case 'h': /* set the global hostname variable*/
                g_hostname = optarg;
                break;

            case 'p': /* set the global port variable */
                g_port = atoi(optarg);
                break;

            case 'u' : /*print usage*/
                printf("Usage: %s [-h HOST] [-p PORT] [-u] \n", args[0]);
                printf ("-h specify remote host for server (default %s)\n", DEFAULT_HOST);
                printf("-p specify port for server (default %d)\n", DEFAULT_PORT);
                printf("-u Print usage\n");
                 break;
        }
    }
}

void loadContext(sgs_context *context)
{
    sgs_ctx_set_channel_joined_cb(context, channel_joined_cb);
    sgs_ctx_set_channel_left_cb(context, channel_left_cb);
    sgs_ctx_set_channel_recv_msg_cb(context, channel_recv_msg_cb);
    sgs_ctx_set_disconnected_cb(context, disconnected_cb);
    sgs_ctx_set_logged_in_cb(context, logged_in_cb);
    sgs_ctx_set_login_failed_cb(context, login_failed_cb);
    sgs_ctx_set_reconnected_cb(context, reconnected_cb);
    sgs_ctx_set_recv_msg_cb(context, recv_msg_cb);
}

int testLogin(sgs_connection *connection)
{

}

int main(int argc, char** argv) {
    sgs_context *context;
    sgs_connection *connection;
    sgs_connection *session;

    /* Begin by initializing the read sets for reading,
     * writing, and exceptions; these sets are all sets
     * of file descriptors
     */
    FD_ZERO(&g_master_readset);
    FD_ZERO(&g_master_writeset);
    FD_ZERO(&g_master_exceptset);

    /* Get any command line argumentss, and
     * set the appropriate (global) variables. Currently,
     * the command line can only specify the host and port
     * of the server, and ask for the usage message
     * to be printed
     */
    getCommandArgs(argc, argv);
    printf("parsed command line; hostname = %s, port = %d\n", g_hostname, g_port);

    /* Create a context object, and load it up with the right set
     * of callbacks. The register_fd and unregister_fd callbacks
     * are loaded as part of the create call for historical purposes
     */
    context = sgs_ctx_create(g_hostname, g_port, register_fd_cb, unregister_fd_cb);
    if (context == NULL) {
        printf("error in context create\n");
        exit(1);
    }
    loadContext(context);
    /*Now, create a connection to the server; if this doesn't work things
     * are messed up enough to require simply printing an error message
     * and getting out
     */
    connection = sgs_connection_create(context);
    if (connection == NULL){
        printf ("error in creating a connection to the server\n");
        exit(1);
    }

    if (testLogin(context) != 0) {
        printf ("unable to log into server\n");
        exit(1);
    }


    
    return (EXIT_SUCCESS);
}