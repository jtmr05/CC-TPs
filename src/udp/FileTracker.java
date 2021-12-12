package udp;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.locks.*;
import java.util.stream.Collectors;

import static packet.Consts.*;
import packet.*;


public class FileTracker implements Closeable {

    private final Map<String, Packet> local;    //what files I have
    private final Map<String, Packet> remote;   //what files the other guy has
    private final Map<String, AckTracker> acks; //keep track of what was sent successfully

    private final Lock localLock;
    private final Lock remoteLock;
    private final Condition cond; //makes a thread wait when there is still metadata to be received
    private final Lock ackLock;

    private boolean hasNext;

    private final File dir;
    private final Thread t;

    /** monitor the directory for any changes */
    private class Monitor implements Runnable {

        private Monitor(){}

        @Override
        public void run() {
            try{
                final int stride = 10, millis = SECONDS_OF_SLEEP * 1000;
                int i = 0;
                while(!Thread.interrupted()){
                    if(i == 0)
                        FileTracker.this.populate();
                    i += stride;
                    if(i >= millis)
                        i = 0;
                    Thread.sleep(stride);
                }
            }
            catch(InterruptedException e){}
        }
    }

    private class AckTracker {

        private final Map<Short, Packet> sent;  
        private short currentSequenceNumber;
        private short biggest;

        protected AckTracker(){
            this.sent = new HashMap<>();
            this.currentSequenceNumber = this.biggest = INIT_SEQ_NUMBER;
        }
    }

    protected void ack(String id, short seqNum){
        this.ackLock.lock();
        var ackTracker = this.acks.get(id);
        
        ackTracker.sent.remove(seqNum);
        
        while(!ackTracker.sent.containsKey(ackTracker.currentSequenceNumber) && 
               ackTracker.currentSequenceNumber < ackTracker.biggest)
            ackTracker.currentSequenceNumber++;
        
        this.ackLock.unlock();
    }

    protected void addToSent(String id, short seqNum, Packet p){
        this.ackLock.lock();
        var ackTracker = this.acks.get(id);
        ackTracker.sent.put(seqNum, p); 
        ackTracker.biggest = (short) Math.max(ackTracker.biggest, seqNum);
        this.ackLock.unlock();
    }

    protected short getCurrentSequenceNumber(String id){
        this.ackLock.lock();
        var ackTracker = this.acks.get(id);
        short s = ackTracker.currentSequenceNumber;
        this.ackLock.unlock();
        return s;
    }

    protected boolean isEmpty(String id){
        this.ackLock.lock();
        var ackTracker = this.acks.get(id);
        boolean b = ackTracker.sent.isEmpty();
        this.ackLock.unlock();
        return b;
    }

    protected Packet getCachedPacket(String id, short seqNum){
        this.ackLock.lock();
        var ackTracker = this.acks.get(id);
        Packet p = ackTracker.sent.get(seqNum);
        this.ackLock.unlock();
        return p;
    }

    protected FileTracker(File dir){
        this.local = new HashMap<>();
        this.remote = new HashMap<>();
        this.acks = new HashMap<>();

        this.localLock = new ReentrantLock();
        this.remoteLock = new ReentrantLock();
        this.cond = this.remoteLock.newCondition();
        this.ackLock = new ReentrantLock();
        
        this.hasNext = false;

        this.dir = dir;
        (this.t = new Thread(new Monitor())).start();
    }

    public String getRemoteFilename(String id){
        this.remoteLock.lock();
        String filename = this.remote.get(id).getFilename();
        this.remoteLock.unlock();
        return filename;
    }

    public long getRemoteCreationTime(String id){
        this.remoteLock.lock();
        long l = this.remote.get(id).getCreationDate();
        this.remoteLock.unlock();
        return l;
    }

    private void populate(){

        if(this.dir.isDirectory()){
            List<File> files = Arrays.stream(this.dir.listFiles()).
                                      filter(File::isFile).
                                      collect(Collectors.toList());
            final int size = files.size();

            try{
                this.localLock.lock(); 
                this.local.clear();        //empty the map before adding new info
                 
                for(int i = 0; i < size; i++){
                    File f = files.get(i);
                    var meta = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
                    String key = this.hashFileMetadata(f, meta);

                    if(!this.local.containsKey(key)){
                        boolean hasNext = i != (size-1);
                        Packet p = new Packet(FILE_META, key, meta.lastModifiedTime().toMillis(), 
                                              meta.creationTime().toMillis(), f.getName(), hasNext);   

                        this.local.put(key, p);
                    }
                }
                this.localLock.unlock();
            }
            catch(IOException e){
                this.localLock.unlock();
            }
        }
    }

    private String hashFileMetadata(File file, BasicFileAttributes meta) throws IOException {

        StringBuilder sb = new StringBuilder();
        sb.append(file.getName()).append(meta.creationTime());

        try{
            MessageDigest md = MessageDigest.getInstance("md5");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));

            BigInteger number = new BigInteger(1, hash); 
            sb.setLength(0); 
            sb.append(number.toString(16));
            while (sb.length() < 32)
                sb.insert(0, '0');
            
            return sb.toString();
        }
        catch(NoSuchAlgorithmException e){
            return null;
        }
    }

    protected boolean putInRemote(String key, Packet value){
        this.remoteLock.lock();
        
        if(!this.hasNext)
            this.remote.clear();        //clear the map when a new batch of Packets arrives
        
        this.hasNext = value.getHasNext();

        if(!this.hasNext)
            this.cond.signalAll();
        
        boolean b = this.remote.put(key, value) != null;
        this.remoteLock.unlock();
        
        return b;
    }

    /**
     * Computes the {@link Set} of packets representing the missing files in the peer directory.
     * @return The set of metadata packets
     */
    protected Set<Packet> toSendSet(){
        this.localLock.lock();
        var localSet = this.local.entrySet();
        this.remoteLock.lock();
        this.localLock.unlock();
        
        try{
            while(this.hasNext)
                try{
                    this.cond.await();
                } 
                catch(InterruptedException e){}
            
            
            var ret = localSet.stream().
                               filter(e -> !this.remote.containsKey(e.getKey())).
                               map(Entry::getValue).
                               collect(Collectors.toSet());
            
            this.ackLock.lock();
            this.acks.clear();
            ret.forEach(p -> this.acks.put(p.getMD5Hash(), new AckTracker()));
            this.ackLock.unlock();
            return ret;
        }
        finally{
            this.remoteLock.unlock();
        }
    }

    protected List<Packet> toSendMetadataList(){
        this.localLock.lock();
        var localSet = this.local.entrySet();
        this.localLock.unlock();

        var list = localSet.stream().
                            map(Entry::getValue).
                            collect(Collectors.toList());

        list.sort((p1, p2) -> Boolean.compare(p2.getHasNext(), p1.getHasNext())); //false comes last
        return list;
    }

    @Override
    public void close(){
        this.t.interrupt();
    }
}