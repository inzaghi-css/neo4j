/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server.web;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import ch.qos.logback.access.jetty.RequestLogImpl;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.MovedContextHandler;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.session.HashSessionManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.neo4j.kernel.guard.Guard;
import org.neo4j.server.database.InjectableProvider;
import org.neo4j.server.guard.GuardingRequestFilter;
import org.neo4j.server.logging.Logger;
import org.neo4j.server.plugins.Injectable;
import org.neo4j.server.security.KeyStoreInformation;
import org.neo4j.server.security.SslSocketConnectorFactory;

import static java.lang.String.format;

public class Jetty9WebServer implements WebServer
{
    private boolean wadlEnabled;
    private Collection<InjectableProvider<?>> defaultInjectables;

    private static class FilterDefinition
	{
		private final Filter filter;
		private final String pathSpec;

		public FilterDefinition(Filter filter, String pathSpec)
		{
			this.filter = filter;
			this.pathSpec = pathSpec;
		}

		public boolean matches(Filter filter, String pathSpec)
		{
			return filter == this.filter && pathSpec.equals(this.pathSpec);
		}

		public Filter getFilter() {
			return filter;
		}

		public String getPathSpec() {
			return pathSpec;
		}
	}
    private static final int DEFAULT_HTTPS_PORT = 7473;
    public static final Logger log = Logger.getLogger( Jetty9WebServer.class );
    public static final int DEFAULT_PORT = 80;
    public static final String DEFAULT_ADDRESS = "0.0.0.0";

    private Server jetty;
    private HandlerCollection handlers;
    private int jettyHttpPort = DEFAULT_PORT;
    private int jettyHttpsPort = DEFAULT_HTTPS_PORT;
    private String jettyAddr = DEFAULT_ADDRESS;

    private final HashMap<String, String> staticContent = new HashMap<String, String>();
    private final Map<String,JaxRsServletHolderFactory> jaxRSPackages =
            new HashMap<String, JaxRsServletHolderFactory>();
    private final Map<String,JaxRsServletHolderFactory> jaxRSClasses =
            new HashMap<String, JaxRsServletHolderFactory>();
    private final List<FilterDefinition> filters = new ArrayList<FilterDefinition>();

    private int jettyMaxThreads = tenThreadsPerProcessor();
    private boolean httpsEnabled = false;
    private KeyStoreInformation httpsCertificateInformation = null;
    private final SslSocketConnectorFactory sslSocketFactory = new SslSocketConnectorFactory();
    private File requestLoggingConfiguration;

    @Override
    public void init()
    {
    }

    @Override
    public void start()
    {
        if ( jetty == null )
        {
            jetty = new Server( new QueuedThreadPool( jettyMaxThreads ) );

            ServerConnector connector = new ServerConnector(jetty);

            connector.setPort( jettyHttpPort );
            connector.setHost( jettyAddr );

            jetty.addConnector( connector );

            if ( httpsEnabled )
            {
                if ( httpsCertificateInformation != null )
                {
                    jetty.addConnector(
                        sslSocketFactory.createConnector( jetty, httpsCertificateInformation, jettyAddr, jettyHttpsPort ) );
                }
                else
                {
                    throw new RuntimeException( "HTTPS set to enabled, but no HTTPS configuration provided." );
                }
            }

        }

        handlers = new HandlerList();

        jetty.setHandler( handlers );

        MovedContextHandler redirector = new MovedContextHandler();

        handlers.addHandler( redirector );

        loadAllMounts();

        startJetty();

    }

    @Override
    public void stop()
    {
        try
        {
            jetty.stop();
            jetty.join();
            jetty = null;
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
    }

    @Override
    public void setPort( int portNo )
    {
        jettyHttpPort = portNo;
    }

    @Override
    public void setAddress( String addr )
    {
        jettyAddr = addr;
    }

    @Override
    public void setMaxThreads( int maxThreads )
    {
        jettyMaxThreads = maxThreads;
    }

    @Override
    public void addJAXRSPackages( List<String> packageNames, String mountPoint, Collection<Injectable<?>> injectables )
    {
        // We don't want absolute URIs at this point
        mountPoint = ensureRelativeUri( mountPoint );
        mountPoint = trimTrailingSlashToKeepJettyHappy( mountPoint );

        JaxRsServletHolderFactory factory = jaxRSPackages.get( mountPoint );
        if ( factory == null )
        {
            factory = new JaxRsServletHolderFactory.Packages();
            jaxRSPackages.put( mountPoint, factory );
        }
        factory.add( packageNames, injectables );

        log.debug( "Adding JAXRS packages %s at [%s]", packageNames, mountPoint );
    }

    @Override
    public void addJAXRSClasses( List<String> classNames, String mountPoint, Collection<Injectable<?>> injectables )
    {
        // We don't want absolute URIs at this point
        mountPoint = ensureRelativeUri( mountPoint );
        mountPoint = trimTrailingSlashToKeepJettyHappy( mountPoint );

        JaxRsServletHolderFactory factory = jaxRSClasses.get( mountPoint );
        if ( factory == null )
        {
            factory = new JaxRsServletHolderFactory.Classes();
            jaxRSClasses.put( mountPoint, factory );
        }
        factory.add( classNames, injectables );

        log.debug( "Adding JAXRS classes %s at [%s]", classNames, mountPoint );
    }

    @Override
    public void setWadlEnabled( boolean wadlEnabled )
    {
        this.wadlEnabled = wadlEnabled;
    }

    @Override
    public void setDefaultInjectables( Collection<InjectableProvider<?>> defaultInjectables )
    {
        this.defaultInjectables = defaultInjectables;
    }

    @Override
    public void removeJAXRSPackages( List<String> packageNames, String serverMountPoint )
    {
        JaxRsServletHolderFactory factory = jaxRSPackages.get( serverMountPoint );
        if ( factory != null )
        {
            factory.remove( packageNames );
        }
    }

    @Override
    public void removeJAXRSClasses( List<String> classNames, String serverMountPoint )
    {
        JaxRsServletHolderFactory factory = jaxRSClasses.get( serverMountPoint );
        if ( factory != null )
        {
            factory.remove( classNames );
        }
    }

    @Override
    public void addFilter(Filter filter, String pathSpec)
    {
        filters.add( new FilterDefinition( filter, pathSpec ) );
    }

    @Override
    public void removeFilter(Filter filter, String pathSpec)
    {
        Iterator<FilterDefinition> iter = filters.iterator();
        while(iter.hasNext())
        {
            FilterDefinition current = iter.next();
            if(current.matches(filter, pathSpec))
            {
                iter.remove();
            }
        }
    }

    @Override
    public void addStaticContent( String contentLocation, String serverMountPoint )
    {
        staticContent.put( serverMountPoint, contentLocation );
    }

    @Override
    public void removeStaticContent( String contentLocation, String serverMountPoint )
    {
        staticContent.remove( serverMountPoint );
    }

    @Override
    public void invokeDirectly( String targetPath, HttpServletRequest request, HttpServletResponse response )
        throws IOException, ServletException
    {
        jetty.handle( targetPath, (Request) request, request, response );
    }

    @Override
    public void setHttpLoggingConfiguration( File logbackConfigFile )
    {
        this.requestLoggingConfiguration = logbackConfigFile;
    }

    @Override
    public void setEnableHttps( boolean enable )
    {
        httpsEnabled = enable;
    }

    @Override
    public void setHttpsPort( int portNo )
    {
        jettyHttpsPort = portNo;
    }

    @Override
    public void setHttpsCertificateInformation( KeyStoreInformation config )
    {
        httpsCertificateInformation = config;
    }

    public Server getJetty()
    {
        return jetty;
    }

    protected void startJetty()
    {
        try
        {
            jetty.start();
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
    }

    private int tenThreadsPerProcessor()
    {
        return 10 * Runtime.getRuntime()
            .availableProcessors();
    }

    private void loadAllMounts()
    {
        SessionManager sm = new HashSessionManager();

        final SortedSet<String> mountpoints = new TreeSet<String>( new Comparator<String>()
        {
            @Override
            public int compare( final String o1, final String o2 )
            {
                return o2.compareTo( o1 );
            }
        } );

        mountpoints.addAll( staticContent.keySet() );
        mountpoints.addAll( jaxRSPackages.keySet() );
        mountpoints.addAll( jaxRSClasses.keySet() );

        for ( String contentKey : mountpoints )
        {
            final boolean isStatic = staticContent.containsKey( contentKey );
            final boolean isJaxrsPackage = jaxRSPackages.containsKey( contentKey );
            final boolean isJaxrsClass = jaxRSClasses.containsKey( contentKey );

            if ( countSet( isStatic, isJaxrsPackage, isJaxrsClass ) > 1 )
            {
                throw new RuntimeException(
                    format( "content-key '%s' is mapped more than once", contentKey ) );
            }
            else if ( isStatic )
            {
                loadStaticContent( sm, contentKey );
            }
            else if ( isJaxrsPackage )
            {
                loadJAXRSPackage( sm, contentKey );
            }
            else if ( isJaxrsClass )
            {
                loadJAXRSClasses( sm, contentKey );
            }
            else
            {
                throw new RuntimeException( format( "content-key '%s' is not mapped", contentKey ) );
            }
        }

        if( requestLoggingConfiguration != null )
        {
            loadRequestLogging();
        }

    }

    private int countSet( boolean... booleans )
    {
        int count = 0;
        for ( boolean bool : booleans )
        {
            if ( bool )
            {
                count++;
            }
        }
        return count;
    }

    private void loadRequestLogging() {
        final RequestLogImpl requestLog = new RequestLogImpl();
        requestLog.setFileName( requestLoggingConfiguration.getAbsolutePath() );

        final RequestLogHandler requestLogHandler = new RequestLogHandler();
        requestLogHandler.setRequestLog( requestLog );
        handlers.addHandler( requestLogHandler );
	}

    private String trimTrailingSlashToKeepJettyHappy( String mountPoint )
    {
        if ( mountPoint.equals( "/" ) )
        {
            return mountPoint;
        }

        if ( mountPoint.endsWith( "/" ) )
        {
            mountPoint = mountPoint.substring( 0, mountPoint.length() - 1 );
        }
        return mountPoint;
    }

    private String ensureRelativeUri( String mountPoint )
    {
        try
        {
            URI result = new URI( mountPoint );
            if ( result.isAbsolute() )
            {
                return result.getPath();
            }
            else
            {
                return result.toString();
            }
        }
        catch ( URISyntaxException e )
        {
            log.debug( "Unable to translate [%s] to a relative URI in ensureRelativeUri(String mountPoint)",
                mountPoint );
            return mountPoint;
        }
    }

    private void loadStaticContent( SessionManager sm, String mountPoint )
    {
        String contentLocation = staticContent.get( mountPoint );
        log.info( "Mounting static content at [%s] from [%s]", mountPoint, contentLocation );
        try
        {
        	SessionHandler sessionHandler = new SessionHandler( sm );
        	sessionHandler.setServer( getJetty() );
            final WebAppContext staticContext = new WebAppContext();
            staticContext.setServer( getJetty() );
            staticContext.setContextPath( mountPoint );
			staticContext.setSessionHandler( sessionHandler );
            URL resourceLoc = getClass().getClassLoader()
                .getResource( contentLocation );
            if ( resourceLoc != null )
            {
                log.debug( "Found [%s]", resourceLoc );
                URL url = resourceLoc.toURI()
                    .toURL();
                final Resource resource = Resource.newResource( url );
                staticContext.setBaseResource( resource );
                log.debug( "Mounting static content from [%s] at [%s]", url, mountPoint );

                addFiltersTo(staticContext);

                handlers.addHandler( staticContext );
            }
            else
            {
                log.error(
                    "No static content available for Neo Server at port [%d], management console may not be available.",
                    jettyHttpPort );
            }
        }
        catch ( Exception e )
        {
            log.error( e );
            e.printStackTrace();
            throw new RuntimeException( e );
        }
    }

    private void loadJAXRSPackage( SessionManager sm, String mountPoint )
    {
        loadJAXRSResource( sm, mountPoint, jaxRSPackages.get( mountPoint ) );
    }

    private void loadJAXRSClasses( SessionManager sm, String mountPoint )
    {
        loadJAXRSResource( sm, mountPoint, jaxRSClasses.get( mountPoint ) );
    }

    private void loadJAXRSResource( SessionManager sm, String mountPoint,
            JaxRsServletHolderFactory jaxRsServletHolderFactory )
    {
        SessionHandler sessionHandler = new SessionHandler( sm );
        sessionHandler.setServer( getJetty() );
        log.debug( "Mounting servlet at [%s]", mountPoint );
        ServletContextHandler jerseyContext = new ServletContextHandler();
        jerseyContext.setServer( getJetty() );
        jerseyContext.setErrorHandler( new NeoJettyErrorHandler() );
        jerseyContext.setContextPath( mountPoint );
        jerseyContext.setSessionHandler( sessionHandler );
        jerseyContext.addServlet( jaxRsServletHolderFactory.create( defaultInjectables, wadlEnabled ), "/*" );
        addFiltersTo(jerseyContext);
        handlers.addHandler(jerseyContext);
    }

    private void addFiltersTo(ServletContextHandler context) {
    	for(FilterDefinition filterDef : filters)
    	{
            context.addFilter( new FilterHolder(
            		filterDef.getFilter() ),
            		filterDef.getPathSpec(), EnumSet.allOf(DispatcherType.class) );
    	}
	}

    @Override
    public void addExecutionLimitFilter( final int timeout, final Guard guard )
    {
        if ( guard == null )
        {
            //TODO enable guard and restart EmbeddedGraphdb
            throw new RuntimeException( "unable to use guard, enable guard-insertion in neo4j.properties" );
        }

        if ( jetty == null )
        {
            throw new RuntimeException( "Jetty server not started before usage");
        }

        jetty.addLifeCycleListener( new JettyLifeCycleListenerAdapter()
        {
            @Override
            public void lifeCycleStarted( LifeCycle arg0 )
            {
                for ( Handler handler : handlers.getHandlers() )
                {
                    if ( handler instanceof ServletContextHandler )
                    {
                        final ServletContextHandler context = (ServletContextHandler) handler;
                        final Filter jettyFilter = new GuardingRequestFilter( guard, timeout );
                        final FilterHolder holder = new FilterHolder( jettyFilter );
                        context.addFilter( holder, "/*", EnumSet.allOf(DispatcherType.class) );
                    }
                }
            }
        } );
    }
}
