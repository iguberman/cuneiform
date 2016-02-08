/*******************************************************************************
 * In the Hi-WAY project we propose a novel approach of executing scientific
 * workflows processing Big Data, as found in NGS applications, on distributed
 * computational infrastructures. The Hi-WAY software stack comprises the func-
 * tional workflow language Cuneiform as well as the Hi-WAY ApplicationMaster
 * for Apache Hadoop 2.x (YARN).
 *
 * List of Contributors:
 *
 * Jörgen Brandt (HU Berlin)
 * Marc Bux (HU Berlin)
 * Ulf Leser (HU Berlin)
 *
 * Jörgen Brandt is funded by the European Commission through the BiobankCloud
 * project. Marc Bux is funded by the Deutsche Forschungsgemeinschaft through
 * research training group SOAMED (GRK 1651).
 *
 * Copyright 2014 Humboldt-Universität zu Berlin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package de.huberlin.wbi.cuneiform.core.cre;

import de.huberlin.wbi.cuneiform.core.actormodel.Message;
import de.huberlin.wbi.cuneiform.core.invoc.Invocation;
import de.huberlin.wbi.cuneiform.core.semanticmodel.JsonReportEntry;
import de.huberlin.wbi.cuneiform.core.semanticmodel.Ticket;
import de.huberlin.wbi.cuneiform.core.ticketsrc.TicketFailedMsg;
import de.huberlin.wbi.cuneiform.core.ticketsrc.TicketFinishedMsg;
import de.huberlin.wbi.cuneiform.core.ticketsrc.TicketSrcActor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;

public class LocalThread implements Runnable {
	
	private static final int WAIT_INTERVAL = 100;
	private static final int MAX_TRIALS = 5;

	private final Invocation invoc;
	private final Log log;
	private final Path buildDir;
	private final TicketSrcActor ticketSrc;
	private final BaseCreActor cre;
	private final Path centralRepo;
	private final Path workDir;

	public LocalThread(TicketSrcActor ticketSrc, BaseCreActor cre,
			Ticket ticket, Path buildDir, Path centralRepo, Path workDir ) {

		if( buildDir == null )
			throw new NullPointerException( "Build directory must not be null." );

		if( !Files.exists( buildDir ) )
			throw new RuntimeException( "Build directory does not exist." );

		if( !Files.isDirectory( buildDir ) )
			throw new RuntimeException( "Build path expected to be a directory." );

		if( cre == null )
			throw new NullPointerException( "CRE actor must not be null." );

		if( ticketSrc == null )
			throw new NullPointerException( "Ticket source must not be null." );
		
		if( centralRepo == null )
			throw new NullPointerException( "Central repository must not be null." );
		
		if( !Files.exists( centralRepo ) )
			throw new RuntimeException( "Central repository does not exist." );
		
		if( !Files.isDirectory( centralRepo ) )
			throw new RuntimeException( "Central repository path expected to be a directory." );
		
		if( workDir == null )
			throw new IllegalArgumentException( "Working directory must not be null." );

		if( !Files.exists( workDir ) )
			throw new RuntimeException( "Working directory does not exist." );
		
		if( !Files.isDirectory( workDir ) )
			throw new RuntimeException( "Working directory expected to be a directory." );

		this.ticketSrc = ticketSrc;
		this.cre = cre;
		this.buildDir = buildDir;
		this.centralRepo = centralRepo;
		this.workDir = workDir;

		invoc = Invocation.createInvocation( ticket );
		log = LogFactory.getLog( LocalThread.class );
	}

	@Override
	public void run() {
		
		Path scriptFile, location, successMarker, reportFile, stdErrFile, stdOutFile;
		// Path lockMarker;
		Process process;
		int exitValue;
		Set<JsonReportEntry> report;
		JsonReportEntry entry;
		String line;
		StringBuffer buf;
		Path srcPath, destPath;
		ProcessBuilder processBuilder;
		Ticket ticket;
		String script, stdOut, stdErr;
		long tic, toc;
		JSONObject obj;
		Message msg;
		Charset cs;
		int trial;
		boolean suc;
		Exception ex;
		Path parent;
		
		
		if( log.isDebugEnabled() )
			log.debug( "Starting up local thread for ticket "+invoc.getTicketId()+"." );
		
		
		if( invoc == null )
			throw new NullPointerException( "Invocation must not be null." );


		ticket = invoc.getTicket();
		process = null;
		stdOut = null;
		stdErr = null;
		// lockMarker = null;
		script = null;
		successMarker = null;
		cs = Charset.forName( "UTF-8" );
		
		try {
					


			// callLocation = Paths.get( System.getProperty( "user.dir" ) );
			location = buildDir.resolve( String.valueOf( invoc.getTicketId() ) );
			// lockMarker = location.resolve( Invocation.LOCK_FILENAME );
			successMarker = location.resolve( Invocation.SUCCESS_FILENAME );
			reportFile = location.resolve( Invocation.REPORT_FILENAME );
			script = invoc.toScript();
			
			// if( Files.exists( lockMarker ) )
			//	throw new IOException( "Lock held on ticket "+invoc.getTicketId() );
			
			if( !Files.exists( successMarker ) ) {
				
				deleteIfExists( location );
				Files.createDirectories( location );			
				
				// Files.createFile( lockMarker );
							
				scriptFile = invoc.getExecutablePath( location );
				
				Files.createFile( scriptFile,
					PosixFilePermissions.asFileAttribute(
							PosixFilePermissions.fromString( "rwxr-x---" ) ) );
				
				// write executable script
				try( BufferedWriter writer = Files.newBufferedWriter( scriptFile, cs, StandardOpenOption.CREATE ) ) {
					writer.write( script );
				}
				
				// write executable log entry
				try( BufferedWriter writer = Files.newBufferedWriter( reportFile, cs, StandardOpenOption.CREATE ) ) {
					writer.write( ticket.getExecutableLogEntry().toString() );
					writer.write( '\n' );
				}
				
				for( String filename : invoc.getStageInList() ) {
					
					
					
					if( filename.charAt( 0 ) == '/' )						
						throw new UnsupportedOperationException( "Absolute path encountered '"+filename+"'." );
						
					srcPath = centralRepo.resolve( filename );
					destPath = location.resolve( filename );
					if( destPath == null )
						throw new NullPointerException( "Link destination path must not be null." );
					
					if( !Files.exists( srcPath ) ) {
						
						srcPath = workDir.resolve( filename );
						if( log.isTraceEnabled() )
							log.trace( "Resolving relative path '"+srcPath+"'." );
					}
					else

						if( log.isTraceEnabled() )
							log.trace( "Resolving path to central repository '"+srcPath+"'." );
					
					if( log.isTraceEnabled() )
						log.trace( "Trying to create symbolic link from '"+srcPath+"' to '"+destPath+"'." );

					parent = destPath.getParent();
					if( parent == null )
						throw new NullPointerException( "Parent path of destination path must not be null." );
					
					if( !Files.exists( parent ) )
						Files.createDirectories( parent );
					
					Files.createSymbolicLink( destPath, srcPath );
					
				}
				
				// run script
				processBuilder = new ProcessBuilder( invoc.getCmd() );
				processBuilder.directory( location.toFile() );
				
				stdOutFile = location.resolve( Invocation.STDOUT_FILENAME );
				stdErrFile = location.resolve( Invocation.STDERR_FILENAME );

				processBuilder.redirectOutput( stdOutFile.toFile() );
				processBuilder.redirectError( stdErrFile.toFile() );

				trial = 1;
				suc = false;
				ex = null;
				tic = System.currentTimeMillis();
				do {
					try {
						process = processBuilder.start();

						suc = true;
					}
					catch( IOException e ) {
						
						ex = e;
						if( log.isWarnEnabled() && trial > 1) // one retry is normal, 2 is a WARN
							log.warn( "Retrying "+ ( ++trial ) +"th time. Waiting "+WAIT_INTERVAL+"ms: "+e.getMessage() );
						Thread.sleep( WAIT_INTERVAL );
					}
				} while( suc == false && trial <= MAX_TRIALS );
				
				if( process == null ) {
					
					if( log.isInfoEnabled() )
						log.info( "Sending ticket failed message because the received process object was null." );
					
					ticketSrc.sendMsg( new TicketFailedMsg( cre, ticket, ex, script, null, null ) );
					// Files.delete( lockMarker );
					return;
				}
					
				
				exitValue = process.waitFor();
				toc = System.currentTimeMillis();				
				
				try( BufferedWriter writer = Files.newBufferedWriter( reportFile, cs, StandardOpenOption.APPEND ) ) {
					
					obj = new JSONObject();
					obj.put( JsonReportEntry.LABEL_REALTIME, toc-tic );
					entry = invoc.createJsonReportEntry( tic, JsonReportEntry.KEY_INVOC_TIME, obj );
					
					writer.write( entry.toString() );
					writer.write( '\n' );
					
					try( BufferedReader reader = Files.newBufferedReader( stdOutFile, cs ) ) {
						
						buf = new StringBuffer();
						while( ( line = reader.readLine() ) != null )
							buf.append( line ).append( '\n' );
						
						
						stdOut = buf.toString();
						
						if( !stdOut.isEmpty() ) {
							entry = invoc.createJsonReportEntry( JsonReportEntry.KEY_INVOC_STDOUT, stdOut );
							writer.write( entry.toString() );
							writer.write( '\n' );
						}
					}
					
					try( BufferedReader reader = Files.newBufferedReader( stdErrFile, cs ) ) {
						
						buf = new StringBuffer();
						while( ( line = reader.readLine() ) != null )
							buf.append( line ).append( '\n' );
						
						stdErr = buf.toString();
						if( !stdErr.isEmpty() ) {
						
							entry = invoc.createJsonReportEntry( JsonReportEntry.KEY_INVOC_STDERR, stdErr );
							writer.write( entry.toString() );
							writer.write( '\n' );
						}
					}
					
					if( exitValue == 0 )
						
						Files.createFile( successMarker );
					
					else {
						
						if( log.isInfoEnabled() )
							log.info( "Sending ticket failed message because exit value was non-zero." );
						
						ticketSrc.sendMsg( new TicketFailedMsg( cre, ticket, null, script, stdOut, stdErr ) );
						// Files.delete( lockMarker );
						return;
						
					}
				}
				

			}
			
			// gather report
			report = new HashSet<>();
			try( BufferedReader reader = Files.newBufferedReader( reportFile, cs ) ) {
				
				while( ( line = reader.readLine() ) != null ) {
					
					line = line.trim();
					
					if( line.isEmpty() )
						continue;
					
					entry = new JsonReportEntry( line );
					
					// If the report comes from the hard cache then the run id
					// is different from the run id of this invocation. This is
					// corrected here.
					entry.setRunId( invoc.getRunId() );
					
					report.add( entry );
				}
				
			}
			
			invoc.evalReport( report );
			
			// create link in central data repository
			for( String f : invoc.getStageOutList() ) {
				
				srcPath = location.resolve( f );
				destPath = centralRepo.resolve( f );
				
				if( Files.exists( destPath ) )
					continue;
				
				if( log.isTraceEnabled() )
					log.trace( "Creating link from "+srcPath+" to "+destPath+"." );
				
				Files.createSymbolicLink( destPath, srcPath );
			}
	
			
			ticketSrc.sendMsg( new TicketFinishedMsg( cre, invoc.getTicket(), report ) );
			
			if( log.isTraceEnabled() )
				log.trace( "Local thread ran through without exception." );
			
			// Files.deleteIfExists( lockMarker );

		}
		catch( InterruptedException e ) {
			
			if( log.isTraceEnabled() )
				log.trace( "Local thread has been interrupted." );
		}
		catch( Exception e ) {
			
			if( log.isTraceEnabled() )
				log.trace( "Something went wrong. Deleting success marker if present." );
			
			if( successMarker != null )
				try {
					Files.deleteIfExists( successMarker );
				}
				catch( IOException e1 ) {
					e1.printStackTrace();
				}
			
			if( log.isInfoEnabled() )
				log.info( "Sending ticket failed message because an exception was caught: "+e.getMessage() );
			
			msg = new TicketFailedMsg( cre, ticket, e, script, stdOut, stdErr );
			
			ticketSrc.sendMsg( msg );
			
		}
		finally {
			
			
			if( process != null ) {
					
					if( log.isDebugEnabled() )
						log.debug( "Stopping local thread for ticket "+invoc.getTicketId()+"." );

					process.destroy();
				}			
		}
	}
	
	public static void deleteIfExists( Path f ) throws IOException {
		
		if( !Files.exists( f, LinkOption.NOFOLLOW_LINKS ) )
			return;
		
		if( Files.isDirectory( f ) )
			try( DirectoryStream<Path> stream = Files.newDirectoryStream( f ) ) {
				for( Path p : stream )
					deleteIfExists( p );
			}
		
		Files.delete( f );
	}
}
