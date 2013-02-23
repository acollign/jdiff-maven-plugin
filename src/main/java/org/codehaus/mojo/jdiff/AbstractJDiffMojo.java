package org.codehaus.mojo.jdiff;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;

import org.apache.commons.lang.SystemUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.sink.SinkFactory;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.reporting.MavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineUtils;

public abstract class AbstractJDiffMojo
    extends AbstractMojo
{

    private static final String JDIFF_CHECKOUT_DIRECTORY = "jdiff.checkoutDirectory";
    /**
     * The javadoc executable.
     */
    @Parameter( property = "javadocExecutable" )
    private String javadocExecutable;
    /**
     * Version to compare the base code against. This will be the left-hand side of the report.
     */
    @Parameter( property = "jdiff.comparisonVersion", defaultValue = "(,${project.version})" )
    private String comparisonVersion;
    /**
     * The base code version. This will be the right-hand side of the report.
     */
    @Parameter( property = "jdiff.baseVersion", defaultValue = "${project.version}" )
    private String baseVersion;
    /**
     * Force a checkout instead of an update when the sources have already been checked out during a previous run. 
     * 
     */
    @Parameter( property = "jdiff.forceCheckout", defaultValue = "false" )
    private boolean forceCheckout;
    
    /**
     * The working directory for this plugin.
     */
    @Parameter( defaultValue = "${project.build.directory}/jdiff", readonly = true )
    private File workingDirectory;
    /**
     * The current build session instance. This is used for toolchain manager API calls.
     */
    @Parameter( defaultValue = "${session}", required = true, readonly = true )
    private MavenSession session;
    @Parameter( defaultValue = "${plugin}", required = true, readonly = true )
    private PluginDescriptor pluginDescriptor;
    /**
     */
    @Parameter( defaultValue = "${project}", required = true, readonly = true )
    private MavenProject project;
    @Parameter( defaultValue = "${reactorProjects}", required = true, readonly = true )
    List<MavenProject> reactorProjects;
    /**
     */
    @Parameter( defaultValue = "${plugin.artifactMap}", required = true, readonly = true )
    private Map<String, Artifact> pluginArtifactMap;
    @Component
    private MavenProjectBuilder mavenProjectBuilder;
    @Component
    private ToolchainManager toolchainManager;
    @Component
    private ScmManager scmManager;
    @Component
    private ArtifactMetadataSource metadataSource;
    @Component
    private ArtifactFactory factory;
    /**
     * The local repository where the artifacts are located.
     */
    @Parameter( defaultValue = "${localRepository}", required = true, readonly = true )
    private ArtifactRepository localRepository;
    /**
     * The remote repositories where artifacts are located.
     */
    @Parameter( defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly = true )
    private List<ArtifactRepository> remoteRepositories;

    /**
     * Holds the packages of both the comparisonVersion and baseVersion
     */
    private Set<String> packages = new HashSet<String>();
    
    private File reportOutputDirectory;

    /**
     * The description of the JDiff report to be displayed in the Maven Generated Reports page (i.e.
     * <code>project-reports.html</code>).
     */
    @Parameter
    private String description;
    /**
     * The name of the JDiff report to be displayed in the Maven Generated Reports page (i.e.
     * <code>project-reports.html</code>).
     */
    @Parameter
    private String name;

    protected MavenProject getProject()
    {
        return project;
    }
    
    public File getReportOutputDirectory()
    {
        return reportOutputDirectory;
    }

    public void executeReport( Locale locale )
        throws MavenReportException
    {
        MavenProject lhsProject, rhsProject;
        try
        {
            lhsProject = resolveProject( comparisonVersion );
            rhsProject = resolveProject( baseVersion );
        }
        catch ( ProjectBuildingException e )
        {
            throw new MavenReportException( e.getMessage() );
        }
        catch ( MojoFailureException e )
        {
            throw new MavenReportException( e.getMessage() );
        }
        catch ( MojoExecutionException e )
        {
            throw new MavenReportException( e.getMessage() );
        }
    
        String lhsTag = lhsProject.getVersion();
        String rhsTag = rhsProject.getVersion();
    
        try
        {
            generateJDiffXML( lhsProject, lhsTag );
            generateJDiffXML( rhsProject, rhsTag );
        }
        catch ( JavadocExecutionException e )
        {
            throw new MavenReportException( e.getMessage(), e );
        }
    
        generateReport( getSourceDirectory( rhsProject.getBuild() ), lhsTag, rhsTag );
        
        try
        {
            IOUtil.copy( getClass().getResourceAsStream( "/black.gif" ), new FileWriter( new File( reportOutputDirectory, "black.gif" ) ) );
        }
        catch ( IOException e )
        {
            getLog().warn( e.getMessage() );
        }
    }

    protected abstract String getSourceDirectory( Build build );

    public boolean isExternalReport()
    {
        return true;
    }

    private MavenProject resolveProject( String versionSpec )
        throws MojoFailureException, MojoExecutionException, ProjectBuildingException
    {
        MavenProject result;
        if ( project.getVersion().equals( versionSpec ) )
        {
            result = project;
        }
        else
        {
            File executionRootDirectory = new File( session.getExecutionRootDirectory() );
            String modulePath  = executionRootDirectory.toURI().relativize( project.getBasedir().toURI() ).getPath();
            
            File checkoutDirectory = (File) session.getPluginContext( pluginDescriptor, reactorProjects.get( 0 ) ).get( JDIFF_CHECKOUT_DIRECTORY );
            result = mavenProjectBuilder.build( new File( checkoutDirectory, modulePath + "pom.xml" ), localRepository, null );
            
            getLog().debug(  new File( checkoutDirectory, modulePath + "pom.xml" ).getAbsolutePath() );
        }
        return result;
    }

    private String getConnection( MavenProject mavenProject )
        throws MojoFailureException
    {
        if ( mavenProject.getScm() == null )
        {
            throw new MojoFailureException( "SCM Connection is not set in your pom.xml." );
        }
    
        String connection = mavenProject.getScm().getConnection();
    
        if ( connection != null )
        {
            if ( connection.length() > 0 )
            {
                return connection;
            }
        }
        connection = mavenProject.getScm().getDeveloperConnection();
    
        if ( StringUtils.isEmpty( connection ) )
        {
            throw new MojoFailureException( "SCM Connection is not set in your pom.xml." );
        }
        return connection;
    }

    private void fetchSources( final File checkoutDir, MavenProject mavenProject )
        throws IOException, MojoFailureException, ScmException
    {
        if ( forceCheckout && checkoutDir.exists() )
        {
            FileUtils.deleteDirectory( checkoutDir );
        }
    
        if ( checkoutDir.mkdirs() )
        {
    
            getLog().info( "Performing checkout to " + checkoutDir );
    
            new ScmCommandExecutor( scmManager, getConnection( mavenProject ), getLog() ).checkout( checkoutDir.getPath() );
        }
        else
        {
            getLog().info( "Performing update to " + checkoutDir );
    
            new ScmCommandExecutor( scmManager, getConnection( mavenProject ), getLog() ).update( checkoutDir.getPath() );
        }
    }

    private void generateJDiffXML( MavenProject project, String tag )
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
            classpathElements.add( getBuildOutputDirectory() );
            classpathElements.addAll( JDiffUtils.getClasspathElements( project ) );
            String classpath = StringUtils.join( classpathElements.iterator(), File.pathSeparator );
            javadoc.addArgumentPair( "classpath", StringUtils.quoteAndEscape( classpath, '\'' ) );
    
            String sourcePath =
                StringUtils.join( JDiffUtils.getProjectSourceRoots( project, getCompileSourceRoots() ).iterator(), File.pathSeparator );
            javadoc.addArgumentPair( "sourcepath", StringUtils.quoteAndEscape( sourcePath, '\'' ) );
    
            Set<String> pckgs = JDiffUtils.getPackages( project.getBasedir(), getCompileSourceRoots() );
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

    protected abstract String getBuildOutputDirectory();

    private String getPluginClasspath()
    {
        //@todo prepend with optional docletArtifacts
        StringBuffer cp = new StringBuffer();
        cp.append( pluginArtifactMap.get( "jdiff:jdiff" ).getFile().getAbsolutePath() );
        cp.append( File.pathSeparatorChar );
        cp.append( pluginArtifactMap.get( "xerces:xercesImpl" ).getFile().getAbsolutePath() );
        cp.append( File.pathSeparatorChar );
        
        return cp.toString();
    }

    private void generateReport( String srcDir, String oldApi, String newApi )
        throws MavenReportException
    {
        try
        {
            getReportOutputDirectory().mkdirs();
            
            JavadocExecutor javadoc = new JavadocExecutor( getJavadocExecutable(), getLog() );
    
            javadoc.addArgument( "-private" );
    
            javadoc.addArgumentPair( "d", getReportOutputDirectory().getAbsolutePath() );
    
            javadoc.addArgumentPair( "sourcepath", srcDir );
    
            List<String> classpathElements = new ArrayList<String>();
            classpathElements.add( getBuildOutputDirectory() );
            classpathElements.addAll( JDiffUtils.getClasspathElements( project ) );
            String classpath = StringUtils.join( classpathElements.iterator(), File.pathSeparator );
            javadoc.addArgumentPair( "classpath", StringUtils.quoteAndEscape( classpath, '\'' ) );
    
            javadoc.addArgumentPair( "doclet", "jdiff.JDiff" );
    
            javadoc.addArgumentPair( "docletpath", getPluginClasspath() );
    
            javadoc.addArgumentPair( "oldapi", oldApi );
    
            javadoc.addArgumentPair( "newapi", newApi );
    
            javadoc.addArgument( "-stats" );
    
            for ( String pckg : packages )
            {
                javadoc.addArgument( pckg );
            }
            
            javadoc.execute( workingDirectory.getAbsolutePath() );
        }
        catch ( IOException e )
        {
            throw new MavenReportException( e.getMessage(), e );
        }
        catch ( JavadocExecutionException e )
        {
            throw new MavenReportException( e.getMessage(), e );
        }
    }

    /** {@inheritDoc} */
    public String getDescription( Locale locale )
    {
        if ( StringUtils.isEmpty( description ) )
        {
            return getBundle( locale ).getString( "report.jdiff.description" );
        }
    
        return description;
    }

    /** {@inheritDoc} */
    public String getName( Locale locale )
    {
        if ( StringUtils.isEmpty( name ) )
        {
            return getBundle( locale ).getString( "report.jdiff.name" );
        }
    
        return name;
    }

    /** {@inheritDoc} */
    public String getOutputName()
    {
        return getDestDir() + "/changes";
    }

    private Artifact resolveArtifact( String versionSpec )
        throws MojoFailureException, MojoExecutionException
    {
        // Find the previous version JAR and resolve it, and it's dependencies
        VersionRange range;
        try
        {
            range = VersionRange.createFromVersionSpec( versionSpec );
        }
        catch ( InvalidVersionSpecificationException e )
        {
            throw new MojoFailureException( "Invalid comparison version: " + e.getMessage() );
        }
    
        Artifact previousArtifact;
        try
        {
            previousArtifact =
                factory.createDependencyArtifact( project.getGroupId(), project.getArtifactId(), range,
                                                  project.getPackaging(), null, Artifact.SCOPE_COMPILE );
    
            if ( !previousArtifact.getVersionRange().isSelectedVersionKnown( previousArtifact ) )
            {
                getLog().debug( "Searching for versions in range: " + previousArtifact.getVersionRange() );
                List<ArtifactVersion> availableVersions =
                    metadataSource.retrieveAvailableVersions( previousArtifact, localRepository,
                                                              project.getRemoteArtifactRepositories() );
                filterSnapshots( availableVersions );
                ArtifactVersion version = range.matchVersion( availableVersions );
                if ( version != null )
                {
                    previousArtifact.selectVersion( version.toString() );
                }
            }
        }
        catch ( OverConstrainedVersionException e1 )
        {
            throw new MojoFailureException( "Invalid comparison version: " + e1.getMessage() );
        }
        catch ( ArtifactMetadataRetrievalException e11 )
        {
            throw new MojoExecutionException( "Error determining previous version: " + e11.getMessage(), e11 );
        }
    
        if ( previousArtifact.getVersion() == null )
        {
            getLog().info( "Unable to find a previous version of the project in the repository" );
        }
        else
        {
            getLog().debug( "Previous version: " + previousArtifact.getVersion() );
        }
    
        return previousArtifact;
    }

    private void filterSnapshots( List<ArtifactVersion> versions )
    {
        for ( Iterator<ArtifactVersion> versionIterator = versions.iterator(); versionIterator.hasNext(); )
        {
            if ( "SNAPSHOT".equals( versionIterator.next().getQualifier() ) )
            {
                versionIterator.remove();
            }
        }
    }

    private ResourceBundle getBundle( Locale locale )
    {
        return ResourceBundle.getBundle( "jdiff-report", locale, this.getClass().getClassLoader() );
    }

    /**
     * Get the path of the Javadoc tool executable depending the user entry or try to find it depending the OS or the
     * <code>java.home</code> system property or the <code>JAVA_HOME</code> environment variable.
     * 
     * @return the path of the Javadoc tool
     * @throws IOException if not found
     */
    private String getJavadocExecutable()
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

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        // first project should do the checkout
        if( project.equals( reactorProjects.get( 0 ) ) )
        {
            Artifact artifact = resolveArtifact( comparisonVersion );
            MavenProject externalProject;
            try
            {
                externalProject = mavenProjectBuilder.buildFromRepository( artifact, remoteRepositories, localRepository );
            }
            catch ( ProjectBuildingException e )
            {
                throw new MojoExecutionException( e.getMessage(), e );
            }
    
            File checkoutDirectory = new File( workingDirectory, externalProject.getVersion() );
            
            try
            {
                fetchSources( checkoutDirectory, externalProject );
                
                session.getPluginContext( pluginDescriptor, project ).put( JDIFF_CHECKOUT_DIRECTORY, checkoutDirectory );
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException( e.getMessage(), e );
            }
            catch ( ScmException e )
            {
                throw new MojoExecutionException( e.getMessage(), e );
            }
        }
        
        if ( !canGenerateReport() )
        {
            return;
        }
    
        try
        {
            Locale locale = Locale.getDefault();
    
            executeReport( locale );
        }
        catch ( MavenReportException e )
        {
            throw new MojoExecutionException( "An error has occurred in " + getName( Locale.ENGLISH )
                + " report generation.", e );
        }
    }

    /** {@inheritDoc} */
    public void generate( org.codehaus.doxia.sink.Sink sink, Locale locale )
        throws MavenReportException
    {
        generate( sink, null, locale );
    }

    public void generate( Sink aSink, Locale aLocale )
        throws MavenReportException
    {
        generate( aSink, null, aLocale );
    }

    /**
     * This method is called when the report generation is invoked by maven-site-plugin.
     * 
     * @param aSink
     * @param aSinkFactory
     * @param aLocale
     * @throws MavenReportException
     */
    public void generate( Sink aSink, SinkFactory aSinkFactory, Locale aLocale )
        throws MavenReportException
    {
        if ( !canGenerateReport() )
        {
            getLog().info( "This report cannot be generated as part of the current build. "
                               + "The report name should be referenced in this line of output." );
            return;
        }
    
        executeReport( aLocale );
    }

    /** {@inheritDoc} */
    public String getCategoryName()
    {
        return MavenReport.CATEGORY_PROJECT_REPORTS;
    }

    /** {@inheritDoc} */
    public void setReportOutputDirectory( File reportOutputDirectory )
    {
        updateReportOutputDirectory( reportOutputDirectory, getDestDir() );
    }
    
    protected void updateReportOutputDirectory( File reportOutputDirectory, String destDir )
    {
        if ( reportOutputDirectory != null && destDir != null
             && !reportOutputDirectory.getAbsolutePath().endsWith( destDir ) )
        {
            this.reportOutputDirectory = new File( reportOutputDirectory, destDir );
        }
        else
        {
            this.reportOutputDirectory = reportOutputDirectory;
        }
    }
    
    protected abstract String getDestDir();

    public boolean canGenerateReport()
    {
       return !getProjectSourceRoots( project ).isEmpty();
    }

    private List<String> getProjectSourceRoots( MavenProject p )
    {
        if ( "pom".equals( p.getPackaging().toLowerCase() ) )
        {
            return Collections.emptyList();
        }
        else
        {
            return getCompileSourceRoots();
        }
    }
    
    protected abstract List<String> getCompileSourceRoots();
}