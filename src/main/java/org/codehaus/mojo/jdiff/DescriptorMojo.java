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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;

/**
 * Generates an API descriptor of the Java sources.
 */
@Mojo( name = "descriptor", requiresDependencyResolution = ResolutionScope.COMPILE )
@Execute( phase = LifecyclePhase.GENERATE_SOURCES )
public class DescriptorMojo
    extends BaseJDiffMojo
{

    /**
     * The JDiff API name.
     */
    @Parameter( defaultValue = "${project.artifactId}-${project.version}" )
    private String apiname;

    /**
     * The output directory.
     */
    @Parameter( defaultValue = "${project.build.outputDirectory}", required = true, readonly = true )
    private String buildOutputDirectory;

    /**
     * The working directory for this plugin.
     */
    @Parameter( defaultValue = "${project.build.directory}/jdiff", readonly = true )
    private File workingDirectory;

    /**
     * List of packages to include separated by space.
     */
    @Parameter( property = "includePackageNames" )
    private String includePackageNames;

    /**
     * {@inheritDoc}
     * 
     * @see org.apache.maven.plugin.AbstractMojo#execute()
     */
    public void execute()
        throws MojoExecutionException
    {
        try
        {
            generateJDiffXML( apiname );
        }
        catch ( JavadocExecutionException e )
        {
            getLog().error( "Error when generating the JDiff descriptor" );
            throw new MojoExecutionException( e.getMessage(), e );
        }
    }

    /**
     * Generates the JDiff XML descriptor.
     * 
     * @param apiname the api name used as a filename
     * @throws JavadocExecutionException thrown if an error occurred during the process
     */
    private void generateJDiffXML( String apiname )
        throws JavadocExecutionException
    {
        try
        {
            JavadocExecutor javadoc = new JavadocExecutor( getJavadocExecutable(), getLog() );

            javadoc.addArgumentPair( "doclet", "jdiff.JDiff" );
            javadoc.addArgumentPair( "docletpath", getDocletpath() );
            javadoc.addArgumentPair( "apiname", apiname );
            javadoc.addArgumentPair( "apidir", workingDirectory.getAbsolutePath() );

            List<String> classpathElements = new ArrayList<String>();
            classpathElements.add( buildOutputDirectory );
            classpathElements.addAll( JDiffUtils.getClasspathElements( project ) );
            String classpath = StringUtils.join( classpathElements.iterator(), File.pathSeparator );
            javadoc.addArgumentPair( "classpath", StringUtils.quoteAndEscape( classpath, '\'' ) );

            String sourcePath =
                StringUtils.join( JDiffUtils.getProjectSourceRoots( project, project.getCompileSourceRoots() ).iterator(), File.pathSeparator );
            javadoc.addArgumentPair( "sourcepath", StringUtils.quoteAndEscape( sourcePath, '\'' ) );

            Set<String> pckgs = new TreeSet<String>();

            if ( !StringUtils.isEmpty( includePackageNames ) )
            {
                List<String> names = Arrays.asList( includePackageNames.split( " " ) );

                getLog().debug( "Included packages (overwritten by [includePackageNames] parameter) : " + names );

                pckgs.addAll( names );
            }
            else
            {
                pckgs = JDiffUtils.getPackages( project.getBasedir(), project.getCompileSourceRoots() );
            }

            for ( String pckg : pckgs )
            {
                javadoc.addArgument( pckg );
            }
            getPackages().addAll( pckgs );

            javadoc.execute( workingDirectory.getAbsolutePath() );
        }
        catch ( IOException e )
        {
            throw new JavadocExecutionException( e.getMessage(), e );
        }
    }

    @Override
    protected List<String> getCompileSourceRoots(MavenProject project)
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected String getBuildOutputDirectory()
    {
        // TODO Auto-generated method stub
        return null;
    }
}