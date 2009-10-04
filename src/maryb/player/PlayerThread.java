package maryb.player;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import maryb.player.io.EmulateSlowInputStream;
import maryb.player.io.SeekablePumpStream;

/**
 *
 * @author cy6ergn0m
 */
/* package */ class PlayerThread extends Thread {

    private final Player parent;

    private volatile boolean dieRequested = false;

    private Bitstream bstream;

    private long mspos = 0;

    private int framesPlayed;

    private final int framesToBePlayed;

    private SourceDataLine line;

    private byte[] buff = new byte[8192];

    private InputStream stream;

    private PlayerState requestedState = null;

    private Decoder decoder;

    private AudioFormat getAudioFormatValue() {
        return new AudioFormat( decoder.getOutputFrequency(),
                16,
                decoder.getOutputChannels(),
                true,
                false );
    }

    public PlayerThread( int framesToBePlayed, Player parent ) {
        super( "player-playback-thread" );
        this.framesToBePlayed = framesToBePlayed;
        this.parent = parent;
    }

    private void createOpenLine() throws LineUnavailableException {
        line = parent.currentDataLine = createLine();
        line.open( getAudioFormatValue() );
        parent.populateVolume( line );
        //line.addLineListener( lineListener );
        line.start();
    }

    private boolean decodeFrame() throws BitstreamException, DecoderException, LineUnavailableException, InterruptedException {
        Header h = bstream.readFrame();
        if( h == null )
            return false;
        SampleBuffer sb = (SampleBuffer) decoder.decodeFrame( h, bstream );

        short[] samples = sb.getBuffer();
        int sz = samples.length << 1;
        if( sz > buff.length ) {
            int limit = buff.length + 1024;
            while( sz > limit )
                limit += 1024;
            buff = new byte[limit];
        }

        int idx = 0;
        for( short s : samples ) {  // TODO: change it!!!!!
            buff[idx++] = (byte) s;
            buff[idx++] = (byte) ( s >> 8 );
        }

        if( line == null ) {
            createOpenLine();    // TODO: бяка!
            parent.totalPlayTimeMcsec = (long) ( 1000. * h.total_ms( parent.realInputStreamLength ) );
        }


        synchronized( parent.osync ) {
            while( parent.getState() == PlayerState.PAUSED_BUFFERING )
                parent.osync.wait();
            if( parent.getState() == PlayerState.STOPPED )
                return false;

            int wasWritten = 0;
            while( wasWritten < idx && !dieRequested && line.isOpen() ) {
                wasWritten += line.write( buff, 0, idx );
            }
        }

        framesPlayed++;
        //written pos in microseconds += (long) (1000.f * h.ms_per_frame());

        bstream.closeFrame();
        return true;
    }

    private void openInputStream() throws IOException {
        String location = parent.getSourceLocation();
        if( location == null )
            throw new IllegalArgumentException();

        if( location.startsWith( "http://" ) ) {
            //HttpURLConnection c =
            URLConnection c = new URL( location ).openConnection();
            c.connect();
            parent.realInputStreamLength = c.getContentLength();
            parent.realInputStream = c.getInputStream();
        } else {
            boolean slow = false;
            if( location.startsWith( "!" ) ) {
                // debug case: slow input stream impl
                location = location.substring( 1 );
                slow = true;
            }
            File f = new File( location );
            if( f.exists() && !f.isDirectory() && f.canRead() )
                parent.realInputStream = new FileInputStream( f );
            else
                throw new IllegalArgumentException( "Bad path to file: '" + location + "'" );
            parent.realInputStreamLength = (int) f.length();
            if( slow )
                parent.realInputStream = new EmulateSlowInputStream( parent.realInputStream, 0.3 );
        }
    }

    private void processID3v2() throws IOException {
//        InputStream is = bstream.getRawID3v2();
//        if( is != null ) {
//            System.out.println( "ID3v2 found" );
//            InputStreamReader isr = new InputStreamReader( is );
//            OutputStreamWriter osw = new OutputStreamWriter( System.out );
//            try {
//                char[] buffer = new char[128];
//                int wasRead;
//
//                while( ( wasRead = isr.read( buffer ) ) != -1 ) {
//                    //osw.write( buffer, 0, wasRead );
//                    }
//                osw.flush();
//                System.out.println();
//                System.out.println( "end of ID3" );
//            } finally {
//                parent.close( isr );
//                close( is );
//            }
//        }
    }

    private void resumeFromPause() {
//        synchronized( parent.osync ) {
//            if( parent.getState() == PlayerState.PAUSED ) {
//                //currentSeekPositionMcsec = getCurrentPosition(); // TODO: is it actualy required?
//                //currentPlayTimeMcsec = 0;
//                }
//        }
    }

    private SourceDataLine createLine() {
        Line newLine = null;

        try {
            newLine = AudioSystem.getLine( getSourceLineInfo() );
        } catch( LineUnavailableException ex ) {
            Logger.getLogger( Player.class.getName() ).log( Level.SEVERE, null, ex );
        }

        if( newLine == null || !( newLine instanceof SourceDataLine ) ) {
            return null; // TODO: handle problem
        }

        return (SourceDataLine) newLine;
    }

    private DataLine.Info getSourceLineInfo() {
        //DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt, 4000);
        DataLine.Info info = new DataLine.Info( SourceDataLine.class, getAudioFormatValue() );
        return info;
    }

    private void skipFrames( long timeMcsec ) throws BitstreamException {
        long skipped = 0;
        Header h;

        while( !dieRequested && ( skipped < timeMcsec ) && ( ( h = bstream.readFrame() ) != null ) ) {
            skipped += (long) ( 1000. * h.ms_per_frame() );
            bstream.closeFrame();
        }
    }

    private void stopAndCloseLine() {
        try {
            line.stop();
        } catch( Throwable ignore ) {
        }
        try {
            line.close();
        } catch( Throwable ignore ) {
        }
    }

    @Override
    public void run() {
        try {
            if( parent.getPumpStream() == null ) {
                openInputStream();
                parent.setPumpStream( new SeekablePumpStream( parent.realInputStream ) );

                new Thread( "player-stream-pump-thread" ) {

                    @Override
                    public void run() {
                        parent.getPumpStream().pumpLoop();
                        synchronized( parent.osync ) {
                            parent.osync.notifyAll();
                        }
                    }

                }.start();
                new PlayerBufferedResolverThread( parent ).start();
            } else {
            }

            resumeFromPause();
            parent.endOfMediaReached = false;

            stream = parent.getPumpStream().openStream();
            bstream = new Bitstream( stream );
            processID3v2();

            decoder = new Decoder();

            line = parent.currentDataLine = null;

            synchronized( parent.seekSync ) {
                while( parent.seekThread != null )
                    parent.seekSync.wait();
            }

            synchronized( parent.osync ) {
                parent.setState( PlayerState.PLAYING );
                parent.osync.notifyAll();
            }
            new PlayerListenerNotificatorThread( parent, this ).start();
            new PlayerHelperThread( parent, this ).start();

            skipFrames( parent.currentSeekPositionMcsec );

            long lastUpdate = 0;

            while( !dieRequested && framesPlayed < framesToBePlayed ) {
                if( !decodeFrame() )
                    break;

                parent.currentPlayTimeMcsec = line.getMicrosecondPosition();

                long now = System.currentTimeMillis();
                parent.lastPlayerActivity = now;
                if( now - lastUpdate > 250 ) {
                    synchronized( parent.osync ) {
                        parent.osync.notifyAll();
                    }
                    lastUpdate = now;
                }

            }

            if( line != null ) {
                line.flush();
                line.drain();
                parent.currentSeekPositionMcsec += line.getMicrosecondPosition();
                parent.currentPlayTimeMcsec = 0;
                parent.endOfMediaReached = !dieRequested;
                System.out.println( "last play position: " + ( line.getMicrosecondPosition() / 1000 ) + " ms" );
            }

        } catch( BitstreamException e ) {
            e.printStackTrace();
        } catch( InterruptedIOException ignore ) {
        } catch( IOException e ) {
            e.printStackTrace();
        } catch( DecoderException e ) {
            e.printStackTrace();
        } catch( LineUnavailableException e ) {
            e.printStackTrace();
        } catch( InterruptedException e ) {
        } finally {
            try {
                if( bstream != null )
                    bstream.close();
            } catch( BitstreamException ignore ) {
            }
            //close(pumpStream);
            //close( realInputStream );
            if( line != null ) {
                mspos = line.getMicrosecondPosition() >> 32;
                stopAndCloseLine();
                line = null;
                parent.currentDataLine = null;
            }

            if( parent.currentPlayerThread.compareAndSet( this, null ) ) {
                // TODO: notify stopped
                synchronized( parent.osync ) {
                    if( requestedState != null ) {
                        switch( requestedState ) {
                            case STOPPED:
                                parent.currentSeekPositionMcsec = 0;
                                parent.setState( requestedState );
                                break;
                            case PAUSED:
                                parent.setState( requestedState );
                                break;
                            default:
                                parent.setState( PlayerState.STOPPED );
                                break;
                        }

                    } else {
                        parent.currentSeekPositionMcsec = 0;
                        parent.currentPlayTimeMcsec = 0;
                        parent.setState( PlayerState.STOPPED );
                    }
                    parent.osync.notifyAll();
                }
            } else {
                synchronized( parent.osync ) {
                    parent.osync.notifyAll();
                }
            }

        }
    }

    public void die( PlayerState requestedState ) {
        if( requestedState != PlayerState.STOPPED && requestedState != PlayerState.PAUSED )
            throw new IllegalArgumentException( "player thead may die only with stopped or paused state" );
        this.requestedState = requestedState;
        dieRequested = true;
        interrupt();
    }

    public void die() {
        die( PlayerState.STOPPED );
    }

}
