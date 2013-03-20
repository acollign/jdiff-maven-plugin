package org.codehaus.mojo.jdiff;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.lang.SystemUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineUtils;

public abstract class BaseJDiffMojo
    extends AbstractMojo
{
    /**
     * The working directory for this plugin.
     */
    @Parameter( defaultValue = "${project.build.directory}/jdiff", readonly = true ) File workingDirectory;
    
    /**
     * The javadoc executable.
     */
    @Parameter( property = "javadocExecutable" )
    private String javadocExecutable;
    
    @Component
    private ToolchainManager toolchainManager;
    
    /**
     * The current build session instance.
     */
    @Parameter( defaultValue = "${session}", required = true, readonly = true )
    private MavenSession session;

    /**
     * Holds the packages of both the comparisonVersion and baseVersion
     */
    private Set<String> packages = new HashSet<String>();

    @Parameter( defaultValue = "${project}", required = true, readonly = true )
    protected MavenProject project;

    @Parameter( defaultValue = "${mojoExecution}", required = true, readonly = true )
    protected MojoExecution mojoExecution;

    protected final MavenSession getSession()
    {
        return session;
    }
    
    protected final Set<String> getPackages()
    {
        return packages;
    }
    
    @SuppressWarnings( "unchecked" )
    protected final Map<String, Artifact> getPluginArtifactMap()
    {
        return mojoExecution.getMojoDescriptor().getPluginDescriptor().getArtifactMap();
    }
    
    protected final PluginDescriptor getPluginDescriptor()
    {
        return mojoExecution.getMojoDescriptor().getPluginDescriptor();
    }
    
    protected void generateJDiffXML( MavenProject project, String tag )
        throws JavadocExecutionException
    {
        try
        {
            JavadocExecutor javadoc = new JavadocExecutor( getJavadocExecutable(), getLog() );
    
            javadoc.addArgumentPair( "doclet", "jdiff.JDiff" );
    
            javadoc.addArgumentPair( "docletpath", getDocletpath() );
    
            javadoc.addArgumentPair( "apiname", tag );
    
            javadoc.addArgumentPair( "apidir", workingDirectory.getAbsolutePath() );
    
            List<String> classpathElements = new ArrayList<String>();
            classpathElements.add( getBuildOutputDirectory() );
            classpathElements.addAll( JDiffUtils.getClasspathElements( project ) );
            String classpath = StringUtils.join( classpathElements.iterator(), File.pathSeparator );
            javadoc.addArgumentPair( "classpath", StringUtils.quoteAndEscape( classpath, '\'' ) );
    
            String sourcePath =
                StringUtils.join( JDiffUtils.getProjectSourceRoots( project, getCompileSourceRoots(project) ).iterator(), File.pathSeparator );
            javadoc.addArgumentPair( "sourcepath", StringUtils.quoteAndEscape( sourcePath, '\'' ) );
    
            Set<String> pckgs = JDiffUtils.getPackages( project.getBasedir(), getCompileSourceRoots(project) );
            for ( String pckg : pckgs )
            {
                javadoc.addArgument( pckg );
            }
            packages.addAll( pckgs );
    
            javadoc.execute( workingDirectory.getAbsolutePath() );
        }
        catch ( IOException e )
        {
            throw new JavadocExecutionException( e.getMessage(), e );
        }
    }

    /**
     * Get the path of the Javadoc tool executable depending the user entry or try to find it depending the OS or the
     * <code>java.home</code> system property or the <code>JAVA_HOME</code> environment variable.
     * 
     * @return the path of the Javadoc tool
     * @throws IOException if not found
     */
    protected final String getJavadocExecutable()
        throws IOException
    {
        Toolchain tc = getToolchain();
    
        if ( tc != null )
        {
            getLog().info( "Toolchain in javadoc-plugin: " + tc );
            if ( javadocExecutable != null )
            {
                getLog().warn( "Toolchains are ignored, 'javadocExecutable' parameter is set to " + javadocExecutable );
            }
            else
            {
                javadocExecutable = tc.findTool( "javadoc" );
            }
        }
    
        String javadocCommand = "javadoc" + ( SystemUtils.IS_OS_WINDOWS ? ".exe" : "" );
    
        File javadocExe;
    
        // ----------------------------------------------------------------------
        // The javadoc executable is defined by the user
        // ----------------------------------------------------------------------
        if ( StringUtils.isNotEmpty( javadocExecutable ) )
        {
            javadocExe = new File( javadocExecutable );
    
            if ( javadocExe.isDirectory() )
            {
                javadocExe = new File( javadocExe, javadocCommand );
            }
    
            if ( SystemUtils.IS_OS_WINDOWS && javadocExe.getName().indexOf( '.' ) < 0 )
            {
                javadocExe = new File( javadocExe.getPath() + ".exe" );
            }
    
            if ( !javadocExe.isFile() )
            {
                throw new IOException( "The javadoc executable '" + javadocExe
                    + "' doesn't exist or is not a file. Verify the <javadocExecutable/> parameter." );
            }
    
            return javadocExe.getAbsolutePath();
        }
    
        // ----------------------------------------------------------------------
        // Try to find javadocExe from System.getProperty( "java.home" )
        // By default, System.getProperty( "java.home" ) = JRE_HOME and JRE_HOME
        // should be in the JDK_HOME
        // ----------------------------------------------------------------------
        // For IBM's JDK 1.2
        if ( SystemUtils.IS_OS_AIX )
        {
            javadocExe =
                new File( SystemUtils.getJavaHome() + File.separator + ".." + File.separator + "sh", javadocCommand );
        }
        else if ( SystemUtils.IS_OS_MAC_OSX )
        {
            javadocExe = new File( SystemUtils.getJavaHome() + File.separator + "bin", javadocCommand );
        }
        else
        {
            javadocExe =
                new File( SystemUtils.getJavaHome() + File.separator + ".." + File.separator + "bin", javadocCommand );
        }
    
        // ----------------------------------------------------------------------
        // Try to find javadocExe from JAVA_HOME environment variable
        // ----------------------------------------------------------------------
        if ( !javadocExe.exists() || !javadocExe.isFile() )
        {
            Properties env = CommandLineUtils.getSystemEnvVars();
            String javaHome = env.getProperty( "JAVA_HOME" );
            if ( StringUtils.isEmpty( javaHome ) )
            {
                throw new IOException( "The environment variable JAVA_HOME is not correctly set." );
            }
            if ( ( !new File( javaHome ).exists() ) || ( !new File( javaHome ).isDirectory() ) )
            {
                throw new IOException( "The environment variable JAVA_HOME=" + javaHome
                    + " doesn't exist or is not a valid directory." );
            }
    
            javadocExe = new File( env.getProperty( "JAVA_HOME" ) + File.separator + "bin", javadocCommand );
        }
    
        if ( !javadocExe.exists() || !javadocExe.isFile() )
        {
            throw new IOException( "The javadoc executable '" + javadocExe
                + "' doesn't exist or is not a file. Verify the JAVA_HOME environment variable." );
        }
    
        return javadocExe.getAbsolutePath();
    }
    
    private Toolchain getToolchain()
    {
        Toolchain tc = null;
        if ( toolchainManager != null )
        {
            tc = toolchainManager.getToolchainFromBuildContext( "jdk", session );
        }
    
        return tc;
    }
    
    protected String getDocletpath()
    {
        //@todo prepend with optional docletArtifacts
        StringBuffer cp = new StringBuffer();
        cp.append( getPluginArtifactMap().get( "jdiff:jdiff" ).getFile().getAbsolutePath() );
        cp.append( File.pathSeparatorChar );
        cp.append( getPluginArtifactMap().get( "xerces:xercesImpl" ).getFile().getAbsolutePath() );
        cp.append( File.pathSeparatorChar );
        
        return cp.toString();
    }
    
    protected abstract List<String> getCompileSourceRoots(MavenProject project);
    
    protected abstract String getBuildOutputDirectory();

    protected MavenProject getProject()
    {
        return project;
    }

}