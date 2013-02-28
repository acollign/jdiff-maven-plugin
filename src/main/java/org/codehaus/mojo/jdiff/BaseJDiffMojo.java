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
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineUtils;

/**
 * Base class of the JDiff mojos.
 *
 * @author Alexandre COLLIGNON (acollign)
 */
public abstract class BaseJDiffMojo
    extends AbstractMojo
{
    /**
     * The output directory.
     */
    @Parameter( defaultValue = "${project.build.outputDirectory}", required = true, readonly = true )
    protected String buildOutputDirectory;

    /**
     * The Maven project.
     */
    @Parameter( defaultValue = "${project}", required = true, readonly = true )
    protected MavenProject project;

    /**
     * Holds the packages of both the comparisonVersion and baseVersion
     */
    protected final Set<String> packages = new HashSet<String>();

    /**
     * The current build session instance. This is used for toolchain manager API calls.
     */
    @Parameter( defaultValue = "${session}", required = true, readonly = true )
    protected MavenSession session;

    /**
     * The working directory for this plugin.
     */
    @Parameter( defaultValue = "${project.build.directory}/jdiff", readonly = true )
    protected File workingDirectory;

    /**
     * List of packages to include separated by space.
     */
    @Parameter( property = "includePackageNames" )
    private String includePackageNames;

    /**
     * The Javadoc executable.
     */
    @Parameter( property = "javadocExecutable" )
    private String javadocExecutable;

    /**
     * The {@link ToolchainManager}.
     */
    @Component
    private ToolchainManager toolchainManager;

    /**
     * Artifacts.
     */
    @Parameter( defaultValue = "${plugin.artifactMap}", required = true, readonly = true )
    private Map<String, Artifact> pluginArtifactMap;

    /**
     * Return the plugin classpath.
     *
     * @return the plugin classpath
     */
    protected String getPluginClasspath()
    {
        // @todo prepend with optional docletArtifacts
        StringBuffer cp = new StringBuffer();
        cp.append( pluginArtifactMap.get( "jdiff:jdiff" ).getFile().getAbsolutePath() );
        cp.append( File.pathSeparatorChar );
        cp.append( pluginArtifactMap.get( "xerces:xercesImpl" ).getFile().getAbsolutePath() );
        cp.append( File.pathSeparatorChar );

        return cp.toString();
    }

    /**
     * Returns the {@link ToolchainManager}.
     *
     * @return the {@link ToolchainManager}
     */
    private Toolchain getToolchain()
    {
        return toolchainManager.getToolchainFromBuildContext( "jdk", session );
    }

    // Borrowed from maven-javadoc-plugin
    /**
     * Get the path of the Javadoc tool executable depending the user entry or try to find it depending the OS or the
     * <code>java.home</code> system property or the <code>JAVA_HOME</code> environment variable.
     *
     * @return the path of the Javadoc tool
     * @throws IOException if not found
     */
    protected String getJavadocExecutable()
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

    /**
     * Generates the JDiff XML descriptor.
     *
     * @param project project from which the XML if generated
     * @param tag identifies the XML file
     * @throws JavadocExecutionException if an error occured during the generation
     */
    protected void generateJDiffXML( MavenProject project, String tag )
        throws JavadocExecutionException
    {
        try
        {
            JavadocExecutor javadoc = new JavadocExecutor( getJavadocExecutable(), getLog() );

            javadoc.addArgumentPair( "doclet", "jdiff.JDiff" );

            javadoc.addArgumentPair( "docletpath", getPluginClasspath() );

            javadoc.addArgumentPair( "apiname", tag );

            javadoc.addArgumentPair( "apidir", workingDirectory.getAbsolutePath() );

            List<String> classpathElements = new ArrayList<String>();
            classpathElements.add( buildOutputDirectory );
            classpathElements.addAll( JDiffUtils.getClasspathElements( project ) );
            String classpath = StringUtils.join( classpathElements.iterator(), File.pathSeparator );
            javadoc.addArgumentPair( "classpath", StringUtils.quoteAndEscape( classpath, '\'' ) );

            String sourcePath =
                StringUtils.join( JDiffUtils.getProjectSourceRoots( project ).iterator(), File.pathSeparator );
            javadoc.addArgumentPair( "sourcepath", StringUtils.quoteAndEscape( sourcePath, '\'' ) );

            Set<String> pckgs = JDiffUtils.getPackages( project );
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
}
