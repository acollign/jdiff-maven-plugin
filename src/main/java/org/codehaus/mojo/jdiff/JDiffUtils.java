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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;

/**
 * Utility-class for this plugin. 
 */
public class JDiffUtils
{
    private JDiffUtils()
    {
        // hide constructor of utility class
    }

    public static List<String> getProjectSourceRoots( MavenProject p )
    {
        if ( "pom".equals( p.getPackaging().toLowerCase() ) )
        {
            return Collections.emptyList();
        }
        return ( p.getCompileSourceRoots() == null ? Collections.EMPTY_LIST
                        : new LinkedList( p.getCompileSourceRoots() ) );
    }

    public static List<String> getClasspathElements( MavenProject project )
    {
        List<String> classpathElements = new ArrayList<String>();

        for ( Artifact a : (List<Artifact>) project.getCompileArtifacts() )
        {
            classpathElements.add( a.getFile().getPath() );
        }
        return classpathElements;
    }

    public static Set<String> getPackages( MavenProject project )
    {
        Set<String> packages = new HashSet<String>();
        List<String> compileRoots = project.getCompileSourceRoots();
        for ( String compileRoot : compileRoots )
        {
            try
            {
                List<String> files =
                    FileUtils.getFileNames( FileUtils.resolveFile( project.getBasedir(), compileRoot ), "**/*.java",
                                            null, false );
                for ( String file : files )
                {
                    packages.add( FileUtils.dirname( file ).replace( File.separatorChar, '.' ) );
                }
            }
            catch ( IOException e )
            {
                // do nothing
            }
        }
        return packages;
    }
}
