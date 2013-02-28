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
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

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
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.reporting.MavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.manager.ScmManager;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;

/**
 * Generates an API difference report between Java sources of two SCM versions
 */
@Mojo( name = "jdiff", requiresDependencyResolution = ResolutionScope.COMPILE )
@Execute( phase = LifecyclePhase.GENERATE_SOURCES )
public class JDiffMojo
    extends BaseJDiffMojo
    implements MavenReport
{

    private static final String JDIFF_CHECKOUT_DIRECTORY = "jdiff.checkoutDirectory";

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
     */
    @Parameter( property = "jdiff.forceCheckout", defaultValue = "false" )
    private boolean forceCheckout;

    /**
     * Specifies the destination directory where javadoc saves the generated HTML files.
     */
    @Parameter( defaultValue = "${project.reporting.outputDirectory}/apidocs", required = true, readonly = true )
    private File reportOutputDirectory;

    /**
     * The name of the destination directory.
     */
    @Parameter( property = "destDir", defaultValue = "apidocs" )
    private String destDir;

    /**
     * The {@link PluginDescriptor}.
     */
    @Parameter( defaultValue = "${plugin}", required = true, readonly = true )
    private PluginDescriptor pluginDescriptor;

    /**
     * Lists of reactors.
     */
    @Parameter( defaultValue = "${reactorProjects}", required = true, readonly = true )
    List<MavenProject> reactorProjects;

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

    @Component
    private MavenProjectBuilder mavenProjectBuilder;

    @Component
    private ScmManager scmManager;

    @Component
    private ArtifactMetadataSource metadataSource;

    @Component
    private ArtifactFactory factory;

    /**
     * Generates the report.
     *
     * @param locale locale used to produce the report
     * @throws MavenReportException if an error occurred when generating the report.
     */
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
            IOUtil.copy( getClass().getResourceAsStream( "/black.gif" ),
                         new FileWriter( new File( reportOutputDirectory, "black.gif" ) ) );
        }
        catch ( IOException e )
        {
            getLog().warn( e.getMessage() );
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see org.apache.maven.reporting.MavenReport#isExternalReport()
     */
    public boolean isExternalReport()
    {
        return true;
    }

    /**
     * Returns the {@link MavenProject} based on the given version.
     *
     * @param versionSpec version of the project
     * @return the {@link MavenProject} based on the given version
     * @throws MojoFailureException
     * @throws MojoExecutionException
     * @throws ProjectBuildingException
     */
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
            String modulePath = executionRootDirectory.toURI().relativize( project.getBasedir().toURI() ).getPath();

            File checkoutDirectory =
                (File) session.getPluginContext( pluginDescriptor, reactorProjects.get( 0 ) ).get( JDIFF_CHECKOUT_DIRECTORY );
            result =
                mavenProjectBuilder.build( new File( checkoutDirectory, modulePath + "pom.xml" ), localRepository, null );

            getLog().debug( new File( checkoutDirectory, modulePath + "pom.xml" ).getAbsolutePath() );
        }
        return result;
    }

    /**
     * Returns the SCM connection string of a {@link MavenProject}.
     *
     * @param mavenProject a {@link MavenProject}
     * @return the SCM connection string of a {@link MavenProject}
     * @throws MojoFailureException if no SCM connection is set
     */
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

    /**
     * Fetches the source code of a {@link MavenProject}.
     *
     * @param checkoutDir directory where to store the source code
     * @param mavenProject {@link MavenProject} identifying the project to fetch
     * @throws IOException if an error occurred when handling checkout directory
     * @throws MojoFailureException if an error occurred when getting project information
     * @throws ScmException if an error occurred when fetching the source code
     */
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

    /**
     * Generates the JDiff report.
     *
     * @param srcDir the directory containing the source code.
     * @param oldApi the identifier of the old API
     * @param newApi the identifier of the new API
     * @throws MavenReportException if an error occured during the generation
     */
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

    /**
     * {@inheritDoc}
     */
    public String getDescription( Locale locale )
    {
        if ( StringUtils.isEmpty( description ) )
        {
            return getBundle( locale ).getString( "report.jdiff.description" );
        }

        return description;
    }

    /**
     * {@inheritDoc}
     */
    public String getName( Locale locale )
    {
        if ( StringUtils.isEmpty( name ) )
        {
            return getBundle( locale ).getString( "report.jdiff.name" );
        }

        return name;
    }

    /**
     * {@inheritDoc}
     */
    public String getOutputName()
    {
        return destDir + "/changes";
    }

    /**
     * Returns the {@link Artifact} of the given version.
     *
     * @param versionSpec version of the {@link Artifact} to search for
     * @return the {@link Artifact} of the given version
     * @throws MojoFailureException
     * @throws MojoExecutionException
     */
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

    /**
     * Removes every Snapshot ArtifactVersion.
     *
     * @param versions list to filter
     */
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

    /**
     * Returns the {@link ResourceBundle}.
     *
     * @param locale the {@link Locale}
     * @return the {@link ResourceBundle}
     */
    private ResourceBundle getBundle( Locale locale )
    {
        return ResourceBundle.getBundle( "jdiff-report", locale, this.getClass().getClassLoader() );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.apache.maven.plugin.AbstractMojo#execute()
     */
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        // first project should do the checkout
        if ( project.equals( reactorProjects.get( 0 ) ) )
        {
            Artifact artifact = resolveArtifact( comparisonVersion );
            MavenProject externalProject;
            try
            {
                externalProject =
                    mavenProjectBuilder.buildFromRepository( artifact, remoteRepositories, localRepository );
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

    /**
     * {@inheritDoc}
     */
    public void generate( org.codehaus.doxia.sink.Sink sink, Locale locale )
        throws MavenReportException
    {
        generate( sink, null, locale );
    }

    /**
     * {@inheritDoc}
     *
     * @see org.apache.maven.reporting.MavenReport#generate(org.codehaus.doxia.sink.Sink, java.util.Locale)
     */
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

    /**
     * {@inheritDoc}
     */
    public String getCategoryName()
    {
        return MavenReport.CATEGORY_PROJECT_REPORTS;
    }

    /**
     * Sets the output directory of the report.
     *
     * @param reportOutputDirectory the output directory
     */
    public void setReportOutputDirectory( File reportOutputDirectory )
    {
        updateReportOutputDirectory( reportOutputDirectory, destDir );
    }

    /**
     * Sets the destination directory.
     *
     * @param destDir destination directory
     */
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

    /**
     * {@inheritDoc}
     */
    public File getReportOutputDirectory()
    {
        return reportOutputDirectory;
    }

    /**
     * Returns true if the report can be generated.
     *
     * @return true if the report can be generated
     */
    public boolean canGenerateReport()
    {
        return !getProjectSourceRoots( project ).isEmpty();
    }

    /**
     * Returns folders containing sources.
     *
     * @param p a {@link MavenProject}
     * @return a list of path containing sources
     */
    private List<String> getProjectSourceRoots( MavenProject p )
    {
        if ( "pom".equals( p.getPackaging().toLowerCase() ) )
        {
            return Collections.emptyList();
        }

        return ( p.getCompileSourceRoots() == null ? Collections.<String> emptyList()
                        : new LinkedList<String>( p.getCompileSourceRoots() ) );
    }

}