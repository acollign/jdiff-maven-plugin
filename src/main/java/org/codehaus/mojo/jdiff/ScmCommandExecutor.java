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

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmResult;
import org.apache.maven.scm.command.checkout.CheckOutScmResult;
import org.apache.maven.scm.command.update.UpdateScmResult;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.repository.ScmRepository;

/**
 *  Executes scm command
 */
public class ScmCommandExecutor
{
    private ScmManager manager;

    private String connectionUrl;
    
    private Log log;

    /**
     * The constructor.
     * 
     * @param manager the scmManager
     * @param connectionUrl the connection URL
     * @param log the mojo logger
     */
    public ScmCommandExecutor( ScmManager manager, String connectionUrl, Log log )
    {
        this.manager = manager;
        
        this.connectionUrl = connectionUrl;
        
        this.log = log;
    }

    /**
     * Check out sources in the {@code targetDirectory}.
     *  
     * @param targetDirectory the directory where the sources will be checked out 
     * @throws ScmException if the checkout throws an exception
     */
    public void checkout( String targetDirectory )
        throws ScmException
    {
        checkout( targetDirectory, null, null );
    }

    /**
     * Check out sources in the {@code targetDirectory}.
     * 
     * @param targetDirectory the directory where the sources will be checked out
     * @param includes the sources to include
     * @param excludes the sources to exclude
     * @throws ScmException if the checkout throws an exception
     */
    public void checkout( String targetDirectory, String includes, String excludes )
        throws ScmException
    {
        try
        {
            ScmRepository repository = manager.makeScmRepository( connectionUrl );

            ScmProvider provider = manager.getProviderByRepository( repository );

            ScmFileSet fileSet = getFileSet( targetDirectory, includes, excludes );

            CheckOutScmResult result = provider.checkOut( repository, fileSet );

            if ( !checkResult( result ) ) 
            {
                throw new ScmException( "checkout failed with provider message" );
            }
        }
        catch ( Exception ex )
        {
            throw new ScmException( "checkout failed.", ex );
        }
    }
    
    /**
     * Update the sources in the {@code targetDirectory}.
     * 
     * @param targetDirectory the directory where the sources will be updated
     * @throws ScmException if the update throws an exception
     */
    public void update( String targetDirectory )
        throws ScmException
    {
        update( targetDirectory, null, null );
    }
    
    /**
     * Update the sources in the {@code targetDirectory}.
     * 
     * @param targetDirectory the directory where the sources will be updated
     * @param includes the sources to include
     * @param excludes the sources to exclude
     * @throws ScmException if the update throws an exception
     */
    public void update( String targetDirectory, String includes, String excludes  )
        throws ScmException
    {
        try
        {
            ScmRepository repository = manager.makeScmRepository( connectionUrl );

            ScmProvider provider = manager.getProviderByRepository( repository );
            
            ScmFileSet fileSet = getFileSet( targetDirectory, includes, excludes );
            
            UpdateScmResult result = provider.update( repository, fileSet );

            if ( !checkResult( result ) )
            {
                throw new ScmException( "checkout failed with provider message" );
            }
        }
        catch ( Exception ex )
        {
            throw new ScmException( "checkout failed.", ex );
        }
    }
    
    private ScmFileSet getFileSet( String path, String includes, String excludes ) throws IOException
    {
        File dir = new File( path );
        
        if ( includes != null || excludes != null )
        {
            return new ScmFileSet( dir, includes, excludes );
        }
        else
        {
            return new ScmFileSet( dir );
        }
    }

    private boolean checkResult( ScmResult result )
    {
        if ( !result.isSuccess() )
        {

            log.warn( "Provider message:" );

            log.warn( result.getProviderMessage() == null ? "" : result.getProviderMessage() );

            log.warn( "Command output:" );

            log.warn( result.getCommandOutput() == null ? "" : result.getCommandOutput() );
            
            return false;
        }
        else 
        {
            return true;
        }
    }
}