package org.codehaus.mojo.jdiff;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
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

/**
 * Generates an API difference report between Java sources of two SCM versions
 * 
 * @goal jdiff
 * @execute phase="generate-sources"
 * @requiresDependencyResolution compile
 */
public class JDiffMojo
    extends AbstractMojo
    implements MavenReport
{

    /**
     * The javadoc executable.
     * 
     * @parameter expression="${javadocExecutable}"
     */
    private String javadocExecutable;

    /**
     * Version to compare the base code against. This will be the left-hand side of the report.
     * 
     * @parameter expression="${jdiff.comparisonVersion}" default-value="(,${project.version})"
     */
    private String comparisonVersion;

    /**
     * The base code version. This will be the right-hand side of the report.
     * 
     * @parameter expression="${jdiff.baseVersion}" default-value="${project.version}"
     */
    private String baseVersion;

    /**
     * Force a checkout instead of an update when the sources have already been checked out during a previous run. 
     * 
     * @parameter expression="${jdiff.forceCheckout}" default-value="false"
     */
    private boolean forceCheckout;
    
    /**
     * Specifies the destination directory where javadoc saves the generated HTML files.
     * 
     * @parameter default-value="${project.reporting.outputDirectory}/apidocs"
     * @required
     * @readonly 
     */
    private File reportOutputDirectory;
    
    /**
     * The name of the destination directory.
     *
     * @parameter expression="${destDir}" default-value="apidocs"
     */
    private String destDir;

    /**
     * @parameter default-value="${project.build.outputDirectory}"
     * @required
     * @readonly
     */
    private String buildOutputDirectory;

    /**
     * The working directory for this plugin.
     * 
     * @parameter default-value="${project.build.directory}/jdiff"
     * @readonly
     */
    private File workingDirectory;

    /**
     * The current build session instance. This is used for toolchain manager API calls.
     * 
     * @parameter expression="${session}"
     * @required
     * @readonly
     */
    private MavenSession session;

    /**
     * @parameter default-value="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * @parameter default-value="${plugin.artifacts}"
     * @required
     * @readonly
     */
    private List<Artifact> pluginArtifacts;

    /** @component */
    private MavenProjectBuilder mavenProjectBuilder;

    /** @component */
    private ToolchainManager toolchainManager;

    /** @component */
    private ScmManager scmManager;

    /** @component */
    private ArtifactMetadataSource metadataSource;

    /** @component */
    private ArtifactFactory factory;

    /**
     * The local repository where the artifacts are located.
     * 
     * @parameter expression="${localRepository}"
     * @readonly
     * @required
     */
    private ArtifactRepository localRepository;

    /**
     * The remote repositories where artifacts are located.
     * 
     * @parameter expression="${project.remoteArtifactRepositories}"
     * @readonly
     * @required
     */
    private List<ArtifactRepository> remoteRepositories;

    /**
     * Holds the packages of both the comparisonVersion and baseVersion
     */
    private Set<String> packages = new HashSet<String>();

    /**
     * The description of the JDiff report to be displayed in the Maven Generated Reports page (i.e.
     * <code>project-reports.html</code>).
     * 
     * @parameter
     */
    private String description;

    /**
     * The name of the JDiff report to be displayed in the Maven Generated Reports page (i.e.
     * <code>project-reports.html</code>).
     * 
     * @parameter
     */
    private String name;

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

        generateReport( rhsProject.getBuild().getSourceDirectory(), lhsTag, rhsTag );
        
        try
        {
            IOUtil.copy( getClass().getResourceAsStream( "/black.gif" ), new FileWriter( new File( reportOutputDirectory, "black.gif" ) ) );
        }
        catch ( IOException e )
        {
            getLog().warn( e.getMessage() );
        }
    }

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
            Artifact artifact = resolveArtifact( versionSpec );
            MavenProject externalProject =
                mavenProjectBuilder.buildFromRepository( artifact, remoteRepositories, localRepository );

            File checkoutDirectory = new File( workingDirectory, externalProject.getVersion() );
            
            try
            {
                fetchSources( checkoutDirectory, externalProject );
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException( e.getMessage() );
            }
            catch ( ScmException e )
            {
                throw new MojoExecutionException( e.getMessage() );
            }

            result = mavenProjectBuilder.build( new File( checkoutDirectory, "pom.xml" ), localRepository, null );
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

    private void fetchSources( File checkoutDir, MavenProject mavenProject ) throws IOException, MojoFailureException, ScmException
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

    private String getPluginClasspath()
    {
        String cp = "";

        for ( Artifact artifact : pluginArtifacts )
        {
            cp += ";" + artifact.getFile().getAbsolutePath();
        }

        return cp.length() > 0 ? cp.substring( 1 ) : cp;
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
            classpathElements.add( buildOutputDirectory );
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
        return destDir + "/changes";
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

    // Borrowed from maven-javadoc-plugin
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
        updateReportOutputDirectory( reportOutputDirectory, destDir );
    }
    
    public void setDestDir( String destDir )
    {
        this.destDir = destDir;
        updateReportOutputDirectory( reportOutputDirectory, destDir );
    }
    
    private void updateReportOutputDirectory( File reportOutputDirectory, String destDir )
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

    /** {@inheritDoc} */
    public File getReportOutputDirectory()
    {
        return reportOutputDirectory;
    }

    /** {@inheritDoc} */
    public boolean canGenerateReport()
    {
        return true;
    }
}