import java.io.*;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.Map;
import java.util.HashMap;
import java.net.URLDecoder;
import java.net.URLEncoder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.HttpURLConnection;

public class Rdfstore
{
  /*
   * Variables that can be specified by command-line argument
   */
  public String template_dir;         // Directory with sparql templates.  Default: ./templates
  public boolean help_only;           // Whether to quit after displaying help info.  Default: false
  public String sparql_addy;          // Address where to send sparql queries to.  Default: http://localhost
  public String sparql_method;        // Method to use for sparql queries (get or post).  Default: get
  public String sparql_update_addy;   // Address where to send sparql updates to.  Default: Mimic sparql_addy
  public String sparql_update_method; // Method to use for sparql updates (get or post).  Default: Mimic sparql_method
  public String sparql_fmt;           // Format to apply to sparl queries.  Default: %s
  public int port;                    // Port for Ricord Rdfstore.java server to listen on.  Default: 20060

  /*
   * Variables not specified by command-line
   */
  ArrayList<Sparql_template> templates;
  URL SparqlURL;

  public static void main(String [] args) throws Exception
  {
    //String x = "Testin\ng [5] Testing";
    //if ( x.matches("(?s).*\\[5\\].*") )
      //System.out.println("T");
    //else
      //System.out.println("F");

    Rdfstore r = new Rdfstore();
    r.run(args);
  }

  public void run(String [] args)
  {
    /*
     * Initialize Rdfstore (including reading template files)
     */
    init_rdfstore(this,args);

    if ( help_only == true )
      return;

    /*
     * Launch server
     */
    System.out.println( "Initiating server..." );

    HttpServer srv;
    try
    {
      srv = HttpServer.create(new InetSocketAddress(port), 0 );
    }
    catch( Exception e )
    {
      System.out.println( "Unable to initiate server!  Is the port already in use?" );
      return;
    }

    for ( Sparql_template t : templates )
      srv.createContext( "/"+t.url, new Rdfstore_NetHandler(this, t) );

    srv.createContext( "/gui", new Rdfstore_NetHandler(this) );

    srv.setExecutor(null);
    srv.start();

    System.out.println( "Server initiated." );

    /*
     * The program will now go dormant, waking up to handle incoming connections.
     */
  }

  public class Sparql_template
  {
    public String text;
    public String name;
    public String url;
  }

  static class Rdfstore_NetHandler implements HttpHandler
  {
    Rdfstore r;
    Sparql_template tmplt;

    public Rdfstore_NetHandler(Rdfstore r, Sparql_template t)
    {
      this.r = r;
      this.tmplt = t;
    }

    public Rdfstore_NetHandler(Rdfstore r)
    {
      this.r = r;
      this.tmplt = null;  /// GUI
    }

    public void handle(HttpExchange t) throws IOException
    {
      Headers requestHeaders = t.getRequestHeaders();

      if ( this.tmplt == null )
      {
        send_gui(t,r);
        return;
      }

      int fJson;
      if ( requestHeaders.get("Accept") != null && requestHeaders.get("Accept").contains("application/json") )
        fJson = 1;
      else
        fJson = 0;

      String response, req;
      try
      {
        req = t.getRequestURI().toString().substring(3+tmplt.url.length());
      }
      catch(Exception e)
      {
        req = "";
      }

      Map<String, String> params = get_args(req);

      System.out.println( "Got request:" );
      for ( Map.Entry<String,String> entry : params.entrySet() )
        System.out.print( "["+entry.getKey()+"]=["+entry.getValue()+"] " );
      System.out.print("\n");

      String query = tmplt.text;
      for ( Map.Entry<String,String> entry : params.entrySet() )
      {
        if ( entry.getValue() == "" && entry.getKey() != "" )
        {
          send_response( t, "Error: An entry in the template was left blank." );
          return;
        }
        query = query.replace("["+entry.getKey()+"]", entry.getValue());
      }

      if ( query.matches("(?s).*\\[[0-9]\\].*") )
      {
        for ( int i = 0; i <= 9; i++ )
        {
          if ( query.matches("(?s).*\\["+i+"\\].*" ) )
          {
            send_response( t, "Error: Template entry "+i+" is missing" );
            return;
          }
        }
      }

      String sparql_answer;

      if ( is_update_query(query) )
      {
        if ( r.sparql_update_method.equals("get") )
          sparql_answer = sparql_query_using_get(r,query,r.sparql_update_addy);
        else
          sparql_answer = sparql_query_using_post(r,query,r.sparql_update_addy,"update");
      }
      else
      {
        if ( r.sparql_method.equals("get") )
          sparql_answer = sparql_query_using_get(r,query,r.sparql_addy);
        else
          sparql_answer = sparql_query_using_post(r,query,r.sparql_addy,"query");
      }

      sparql_answer = escapeHTML(sparql_answer);

      if ( sparql_answer.charAt(0) == '?' )
      {
        if ( sparql_answer.contains("\n") )
          sparql_answer = sparql_answer.substring(sparql_answer.indexOf("\n"));
      }

      if ( sparql_answer.trim().length() == 0 )
        sparql_answer = "No results.";

      send_response( t, "<pre>"+sparql_answer+"</pre>" );
    }

    public void send_server_error(HttpExchange t)
    {
      send_response( t, "500 Server Error.  Most likely, what this means is that Ricordo Rdfstore couldn't communicate with the sparql endpoint." );
    }

    public static String sparql_query_using_post(Rdfstore r, String query, String url, String keyname)
    {
      try
      {
        StringBuilder postData = new StringBuilder();
        postData.append(keyname+"="+URLEncoder.encode(query, "UTF-8"));
        byte[] postDataBytes = postData.toString().getBytes("UTF-8");

        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
        c.setDoOutput(true);
        c.getOutputStream().write(postDataBytes);

        Reader in = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder answer = new StringBuilder();
        for ( int x; (x = in.read()) >= 0; answer.append((char)x) );
        return answer.toString();
      }
      catch( Exception e )
      {
        return "500 Server Error.  Most likely, what this means is that Ricordo Rdfstore couldn't communicate with your sparql endpoint.";
      }
    }

    public static String sparql_query_using_get(Rdfstore r, String query, String url)
    {
      URL u;
      HttpURLConnection c;
      String encoded=null;
      try
      {
        u = new URL(url + URLEncoder.encode(query,"UTF-8"));
        c = (HttpURLConnection) u.openConnection();

        c.setConnectTimeout(2000); //To do: make this configurable.
        c.setReadTimeout(5000);  //This too.
      }
      catch(Exception e)
      {
        return "500 Server Error.  Most likely, what this means is that Ricordo Rdfstore couldn't communicate with your sparql endpoint.";
      }

      Scanner sc=null;
      try
      {
        sc = new Scanner(c.getInputStream());
      }
      catch(Exception e)
      {
        return "500 Server Error.  Most likely, what this means is that Ricordo Rdfstore couldn't communicate with your sparql endpoint.";
      }

      return sc.useDelimiter("\\A").next();
    }

    /*
     * escapeHTML thanks to Bruno Eberhard
     */
    public static String escapeHTML(String s)
    {
      StringBuilder out = new StringBuilder(Math.max(16, s.length()));

      for (int i = 0; i < s.length(); i++)
      {
        char c = s.charAt(i);

        if (c > 127 || c == '"' || c == '<' || c == '>' || c == '&')
        {
          out.append("&#");
          out.append((int) c);
          out.append(';');
        }
        else
          out.append(c);
      }
      return out.toString();
    }
  }

  static public void send_response( HttpExchange t, String response )
  {
    try
    {
      Headers h = t.getResponseHeaders();
      h.add("Cache-Control", "no-cache, no-store, must-revalidate");
      h.add("Pragma", "no-cache");
      h.add("Expires", "0");

      t.sendResponseHeaders(200,response.getBytes().length);
      OutputStream os = t.getResponseBody();
      os.write(response.getBytes());
      os.close();
    }
    catch(Exception e)
    {
      ;
    }
  }


  public void init_rdfstore(Rdfstore r, String [] args)
  {
    parse_commandline(r, args);

    try
    {
      r.SparqlURL = new URL(r.sparql_addy);
    }
    catch(Exception e)
    {
      System.out.println( "Unable to create URL object for "+r.sparql_addy+"." );
      System.out.println( "If that's not the correct address for your sparql endpoint, rerun with commandline argument -endpoint <address>." );
      r.help_only = true;
      return;
    }

    if ( r.help_only == true )
      return;

    load_template_files();
  }

  public void load_template_files()
  {
    File folder;
    File[] templatefiles = null;

    try
    {
      folder = new File(template_dir);
      templatefiles = folder.listFiles();
    }
    catch( Exception e )
    {
      folder = null;
    }

    if ( folder == null || templatefiles == null )
    {
      System.out.println( "Couldn't open SPARQL template directory "+template_dir+"." );
      System.out.println( "If that's not the correct template directory, rerun Rdfstore with commandline -templates <path to template directory>" );
      System.out.println( "Please also make sure Java has permission to view the directory." );
      System.out.println( "" );
      help_only = true;
      return;
    }

    System.out.println( "Loading templates..." );

    boolean fFile = false;
    templates = new ArrayList<Sparql_template>();

    for ( File f : templatefiles )
    {
      if ( f.isFile() )
      {
        add_template( f );
        fFile = true;
      }
    }

    if ( fFile == false )
    {
      System.out.println( "The SPARQL template directory, "+template_dir+", does not seem to contain any template files!" );
      System.out.println( "If that's not the correct template directory rerun Rdfstore with commandline -template <path to template directory>" );
      System.out.println( "" );
      help_only = true;
      return;
    }
  }

  public void add_template( File f )
  {
    Sparql_template t = new Sparql_template();

    try
    {
      t.text = new Scanner(f).useDelimiter("\\A").next();
      t.name = parse_template_name(f.getName());
      t.url = parse_template_url(f.getName());
    }
    catch ( Exception e )
    {
      e.printStackTrace();
    }

    if ( t.url.contains( " " ) || t.url.contains( "/" ) || t.url.contains( "\\" ) )
    {
      System.out.println( "Warning: Template file \""+f.getName()+"\" contains illegal characters in its name, and is not being added as a template." );
      return;
    }

    templates.add( t );
  }

  public String parse_template_url( String x )
  {
    String retval;

    if ( x.length() > 4 && x.substring( x.length() - 4 ).equals( ".txt" ) )
      retval = x.substring( 0, x.length() - 4 );
    else
      retval = x;

    return retval;
  }

  public String parse_template_name( String x )
  {
    String retval = parse_template_url( x );

    retval = retval.replace("_", " ");

    return retval;
  }

  public void parse_commandline(Rdfstore r, String [] args)
  {
    r.template_dir = "./templates";
    r.help_only = false;
    r.sparql_addy = "http://localhost";
    r.sparql_update_addy = "http://localhost";
    r.sparql_method = "get";
    r.sparql_update_method = "get";
    r.sparql_fmt = "%s";
    r.port = 20060;

    int i;
    String flag;
    int fSparqlUpdateAddy = 0;
    int fSparqlUpdateMethod = 0;

    for ( i = 0; i < args.length; i++ )
    {
      if ( args[i].length() > 2 && args[i].substring(0,2).equals("--") )
        flag = args[i].substring(2).toLowerCase();
      else if ( args[i].length() > 1 && args[i].substring(0,1).equals("-") )
        flag = args[i].substring(1).toLowerCase();
      else
        flag = args[i].toLowerCase();

      if ( flag.equals("help") || flag.equals("h") )
      {
        System.out.println( "Command line options are as follows:"                  );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-templates <path to directory>"                        );
        System.out.println( "(Specifies path to directory with query templates)"    );
        System.out.println( "(Default: ./templates)"                                );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-endpoint <URL>"                                       );
        System.out.println( "(Specifies the sparql query endpoint location)"        );
        System.out.println( "(Default: http://localhost)"                           );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-method GET, or -method POST"                          );
        System.out.println( "(Specifies which HTTP method to use for queries)"      );
        System.out.println( "(Default: GET)"                                        );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-update <URL>"                                         );
        System.out.println( "(Specifies the sparql update endpoint location)"       );
        System.out.println( "(For cases when sparql update has different URL than"  );
        System.out.println( " query, e.g. Fuseki)"                                  );
        System.out.println( "(Default: Mimics 'endpoint')"                          );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-updatemethod GET, or -updatemethod POST"              );
        System.out.println( "(Which HTTP method to use for updates)"                );
        System.out.println( "(Default: Mimics 'method')"                            );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-format <format>"                                      );
        System.out.println( "(A string, containing %s.  The %s will be replaced by" );
        System.out.println( " the query itself, and the resulting string will be"   );
        System.out.println( " sent to the sparql endpoint.  Good for things like"   );
        System.out.println( " triplestore-specific preambles, etc.)"                );
        System.out.println( "(Default: %s)"                                         );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-port <number>"                                        );
        System.out.println( "(Which port should this Rdfstore program listen on?)"  );
        System.out.println( "(Default: 20060)"                                      );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-help"                                                 );
        System.out.println( "(Displays this helpfile)"                              );
        System.out.println();
        r.help_only = true;
        return;
      }

      if ( flag.equals("templates") || flag.equals("temp") || flag.equals("template") )
      {
        if ( i+1 >= args.length )
        {
          System.out.println( "Specify a path to a directory containing sparql template files." );
          r.help_only = true;
          return;
        }
        r.template_dir = args[++i];
        System.out.println( "Using "+r.template_dir+" as template directory." );
      }
      else if ( flag.equals("addy") || flag.equals("address") || flag.equals("sparql_addy") || flag.equals("sparql_address") || flag.equals("endpt") || flag.equals("endpoint") )
      {
        if ( i+1 >= args.length )
        {
          System.out.println( "Specify the address of the SPARQL query endpoint." );
          r.help_only = true;
          return;
        }
        r.sparql_addy = args[++i];
        System.out.println( "Using "+r.sparql_addy+" as SPARQL query endpoint." );

        if ( fSparqlUpdateAddy == 0 )
          r.sparql_update_addy = r.sparql_addy;
      }
      else if ( flag.equals("update" ) || flag.equals("upd") )
      {
        fSparqlUpdateAddy = 1;

        if ( i+1 >= args.length )
        {
          System.out.println( "Specify the address of the sparql update endpoint." );
          r.help_only = true;
          return;
        }
        r.sparql_update_addy = args[++i];
        System.out.println( "Using "+r.sparql_update_addy+" as SPARQL update endpoint." );
      }
      else if ( flag.equals("method") || flag.equals("mthd") || flag.equals("sparql_method") )
      {
        if ( i+1 >= args.length || (!args[i+1].equals("get") && !args[i+1].equals("post") && !args[i+1].equals("GET") && !args[i+1].equals("POST")) )
        {
          System.out.println( "Valid methods are:  GET, or POST" );
          r.help_only = true;
          return;
        }
        r.sparql_method = args[++i].toLowerCase();
        System.out.println( "Using "+args[i]+" as SPARQL query HTTP method" );

        if ( fSparqlUpdateMethod == 0 )
          r.sparql_update_method = r.sparql_method;
      }
      else if ( flag.equals("updatemethod") || flag.equals("update_method") || flag.equals("updatemthd") || flag.equals("update_mthd") )
      {
        fSparqlUpdateMethod = 1;

        if ( i+1 >= args.length || (!args[i+1].equals("get") && !args[i+1].equals("post") && !args[i+1].equals("GET") && !args[i+1].equals("POST")) )
        {
          System.out.println( "Valid update methods are:  GET, or POST" );
          r.help_only = true;
          return;
        }
        r.sparql_update_method = args[++i].toLowerCase();
        System.out.println( "Using "+args[i]+" as SPARQL update HTTP method" );
      }
      else if ( flag.equals("fmt") || flag.equals("format") || flag.equals("sparql_fmt") || flag.equals("sparql_format") )
      {
        if ( i+1 >= args.length || !args[i+1].contains("%s") )
        {
          System.out.println( "Specify a format for sparql queries.  The format should be a string, with \"%s\" where you want the actual query itself to be filled in." );
          r.help_only = true;
          return;
        }
        r.sparql_fmt = args[++i];
        System.out.println( "Using "+r.sparql_fmt+" as sparql format." );
      }
      else if ( flag.equals("port") || flag.equals("p") )
      {
        if ( i+1 < args.length )
        {
          try
          {
            r.port = Integer.parseInt(args[i+1]);
          }
          catch( Exception e )
          {
            System.out.println( "Port must be a number." );
            r.help_only = true;
            return;
          }
          System.out.println( "Rircordo Rdfstore will listen on port "+args[++i] );
        }
        else
        {
          System.out.println( "Which port do you want the server to listen on?" );
          r.help_only = true;
          return;
        }
      }
      else if ( flag.equals("get") || flag.equals("post") )
      {
        String [] reargs = {"method", flag};
        parse_commandline( r, reargs );
      }
    }
  }

  public static void send_gui(HttpExchange t, Rdfstore r)
  {
    String the_html;
    String the_js;

    try
    {
      the_html = new Scanner(new File("gui.php")).useDelimiter("\\A").next();
      the_js   = new Scanner(new File("gui.js")).useDelimiter("\\A").next();
    }
    catch(Exception e)
    {
      send_response( t, "The GUI could not be sent, due to a problem with the html file or the javascript file." );
      return;
    }

    the_html = the_html.replace("@JAVASCRIPT", "<script type='text/javascript'>"+the_js+"</script>");

    String the_menu = "<select id='pulldown' onchange='pulldownchange();'><option value='nosubmit' selected='selected'>Choose Template</option>";
    for ( Sparql_template tmplt : r.templates )
      the_menu += "<option value='"+tmplt.url+"'>"+tmplt.name+"</option>";

    the_menu += "</select>";

    the_html = the_html.replace("@PULLDOWNMENU", the_menu);

    String ifchecks = "if ( templatename == 'nosubmit' )\n    hide_input_boxes();\n  else\n";
    for ( Sparql_template tmplt : r.templates )
    {
      if ( tmplt.text.matches("(?s).*\\[[0-9]\\].*") == false )
        ifchecks += "  if ( templatename == '"+tmplt.url+"' )\n    hide_input_boxes();\n  else\n";
      else
      {
        ifchecks += "  if ( templatename == '"+tmplt.url+"' )\n    show_input_boxes('";
        for ( int i = 0; i <= 9; i++ )
        {
          if ( tmplt.text.contains("["+i+"]") )
            ifchecks += i;
        }
        ifchecks += "');\n  else\n";
      }
    }

    ifchecks += "    hide_input_boxes();\n";

    ifchecks += "  if ( templatename == 'nosubmit' )\n    hide_input_button();\n  else\n    show_input_button();";

    the_html = the_html.replace("@CHANGESELECTIONCODE", ifchecks);

    send_response( t, the_html );
  }

  public static Map<String, String> get_args(String query)
  {
    Map<String, String> result = new HashMap<String, String>();
    try
    {
      for (String param : query.split("&"))
      {
        String pair[] = param.split("=");
        if (pair.length > 1)
          result.put(URLDecoder.decode(pair[0],"UTF-8"), URLDecoder.decode(pair[1],"UTF-8"));
        else
          result.put(URLDecoder.decode(pair[0],"UTF-8"), "");
      }
    }
    catch( Exception e )
    {
      ;
    }
    return result;
  }

  public static boolean is_update_query( String q )
  {
    String lower = q.toLowerCase();

    if ( lower.substring(0,6).equals("insert") )
      return true;

    if ( lower.substring(0,6).equals("delete") )
      return true;

    if ( lower.substring(0,4).equals("load") )
      return true;

    if ( lower.substring(0,6).equals("create") )
      return true;

    if ( lower.substring(0,5).equals("clear") )
      return true;

    if ( lower.substring(0,3).equals("add") )
      return true;

    if ( lower.substring(0,4).equals("move") )
      return true;

    if ( lower.substring(0,4).equals("drop") )
      return true;

    return false;
  }
}
