package utils;

import java.io.*;

import static packet.Consts.*;

public class FileChunkReader implements Closeable {
    
    private final BufferedInputStream reader;
    private boolean finished;

    public FileChunkReader(String filename) throws FileNotFoundException {
        this.reader = new BufferedInputStream(new FileInputStream(new File(filename)));
        this.finished = false;
    }

    public boolean isFinished(){
        return this.finished;
    }

    public byte[] nextChunk() throws AllChunksReadException {
        if(!this.isFinished()){
            byte[] data, buffer = new byte[DATA_SIZE];
            try{
                final int bread = this.reader.read(buffer);
                
                if(bread != -1)
                    if(bread < DATA_SIZE){
                        data = new byte[bread];
                        System.arraycopy(buffer, 0, data, 0, bread); 
                    }
                    else
                        data = buffer;
                else
                    data = null;
                
                this.finished = bread < buffer.length;
            }
            catch(IOException e){
                data = null;
                this.finished = true;
            }
            return data;
        }
        else
            throw new AllChunksReadException();
    }

    @Override
    public void close(){
        try{
            this.reader.close();
        }
        catch(IOException e){}
    }
}

/**
 *     public byte[] indexedChunk(int off){

        
        byte[] data, buffer = new byte[DATA_SIZE];
        try{
            this.reader.seek(off);
            final int bread = this.reader.read(buffer, 0, buffer.length);

            if(bread != -1){
                if(bread < DATA_SIZE){
                    data = new byte[bread];
                    System.arraycopy(buffer, 0, data, 0, bread); 
                }
                else
                    data = buffer;
            }
            else
                data = null;
        }
        catch(IOException e){
            data = null;
        }
        return data;
    }

 */