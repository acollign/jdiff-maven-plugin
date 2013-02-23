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
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.apache.maven.model.Build;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.reporting.MavenReport;

/**
 * Generates an API difference report between Java sources of two SCM versions
 */
@Mojo( name = "jdiff", requiresDependencyResolution = ResolutionScope.COMPILE )
@Execute( phase = LifecyclePhase.GENERATE_SOURCES )
public class JDiffMojo
    extends AbstractJDiffMojo
    implements MavenReport
{
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

    @SuppressWarnings( "unchecked" )
    protected List<String> getCompileSourceRoots()
    {
        return ( getProject().getCompileSourceRoots() == null
            ? Collections.<String>emptyList()
            : new LinkedList<String>( getProject().getCompileSourceRoots() ) );
    }
    
    public void setDestDir( String destDir )
    {
        this.destDir = destDir;
        updateReportOutputDirectory( reportOutputDirectory, destDir );
    }
    
    protected String getDestDir()
    {
        return destDir;
    }
    
    @Override
    protected String getBuildOutputDirectory()
    {
        return getProject().getBuild().getOutputDirectory();
    }
    
    @Override
    protected String getSourceDirectory( Build build )
    {
        return build.getSourceDirectory();
    }

}