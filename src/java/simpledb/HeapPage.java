package simpledb;

import java.util.*;
import java.io.*;

/**
 * Each instance of HeapPage stores data for one page of HeapFiles and //还没写
 * implements the Page interface that is used by BufferPool.
 *
 * @see HeapFile
 * @see BufferPool
 *
 */
public class HeapPage implements Page {

    final HeapPageId pid;
    final TupleDesc td;     //calculate tuple size
    final byte header[];
    final Tuple tuples[];
    final int numSlots;

    byte[] oldData;
    private final Byte oldDataLock=new Byte((byte)0);

    private boolean isDirty;
    private TransactionId tid;

    /**
     * Create a HeapPage from a set of bytes of data read from disk.
     * The format of a HeapPage is a set of header bytes indicating
     * the slots of the page that are in use, some number of tuple slots.
     *  Specifically, the number of tuples is equal to: <p>
     *          floor((BufferPool.getPageSize()*8) / (tuple size * 8 + 1))
     * <p> where tuple size is the size of tuples in this
     * database table, which can be determined via {@link Catalog#getTupleDesc}.
     * The number of 8-bit header words is equal to:
     * <p>
     *      ceiling(no. tuple slots / 8)        //一个bite 8 bits 1slot=1bit 看占用的
     * <p>
     * @see Database#getCatalog
     * @see Catalog#getTupleDesc
     * @see BufferPool#getPageSize()
     */
    public HeapPage(HeapPageId id, byte[] data) throws IOException {
        this.pid = id;
        this.td = Database.getCatalog().getTupleDesc(id.getTableId());
        this.numSlots = getNumTuples();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));

        this.isDirty=false;
        this.tid=null;

        // allocate and read the header slots of this page
        header = new byte[getHeaderSize()];
        for (int i=0; i<header.length; i++)
            header[i] = dis.readByte();
        
        tuples = new Tuple[numSlots];
        try{
            // allocate and read the actual records of this page
            for (int i=0; i<tuples.length; i++)
                tuples[i] = readNextTuple(dis,i);
        }catch(NoSuchElementException e){
            e.printStackTrace();
        }
        dis.close();

        setBeforeImage();
    }

    /** Retrieve the number of tuples on this page.
        @return the number of tuples on this page
    */
    private int getNumTuples() {        
        // some code goes here
        //return numSlots;  <--  !!!看构造函数
        int tupleSize=td.getSize();
        int rt=(int)Math.floor((BufferPool.getPageSize()*8)*1.0 / (tupleSize * 8 + 1));
        return rt;
    }

    /**
     * Computes the number of bytes in the header of a page in a HeapFile with each tuple occupying tupleSize bytes
     * @return the number of bytes in the header of a page in a HeapFile with each tuple occupying tupleSize bytes
     */
    private int getHeaderSize() {        
        
        // some code goes here
        return (int)Math.ceil(numSlots*1.0/8);
                 
    }
    
    /** Return a view of this page before it was modified
        -- used by recovery */
    public HeapPage getBeforeImage(){
        try {
            byte[] oldDataRef = null;
            synchronized(oldDataLock)
            {
                oldDataRef = oldData;
            }
            return new HeapPage(pid,oldDataRef);
        } catch (IOException e) {
            e.printStackTrace();
            //should never happen -- we parsed it OK before!
            System.exit(1);
        }
        return null;
    }
    
    public void setBeforeImage() {
        synchronized(oldDataLock)
        {
        oldData = getPageData().clone();
        }
    }

    /**
     * @return the PageId associated with this page.
     */
    public HeapPageId getId() {
    // some code goes here
    //throw new UnsupportedOperationException("implement this");
        return pid;
    }

    /**
     * Suck up tuples from the source file.
     */
    private Tuple readNextTuple(DataInputStream dis, int slotId) throws NoSuchElementException {
        // if associated bit is not set, read forward to the next tuple, and
        // return null.
        if (!isSlotUsed(slotId)) {
            for (int i=0; i<td.getSize(); i++) {
                try {
                    dis.readByte();
                } catch (IOException e) {
                    throw new NoSuchElementException("error reading empty tuple");
                }
            }
            return null;
        }

        // read fields in the tuple
        Tuple t = new Tuple(td);
        RecordId rid = new RecordId(pid, slotId);
        t.setRecordId(rid);
        try {
            for (int j=0; j<td.numFields(); j++) {
                Field f = td.getFieldType(j).parse(dis);
                t.setField(j, f);
            }
        } catch (java.text.ParseException e) {
            e.printStackTrace();
            throw new NoSuchElementException("parsing error!");
        }

        return t;
    }

    /**
     * Generates a byte array representing the contents of this page.
     * Used to serialize this page to disk.
     * <p>
     * The invariant here is that it should be possible to pass the byte
     * array generated by getPageData to the HeapPage constructor and
     * have it produce an identical HeapPage object.
     *
     * @see #HeapPage
     * @return A byte array correspond to the bytes of this page.
     */
    public byte[] getPageData() {
        int len = BufferPool.getPageSize();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(len);
        DataOutputStream dos = new DataOutputStream(baos);

        // create the header of the page
        for (int i=0; i<header.length; i++) {
            try {
                dos.writeByte(header[i]);
            } catch (IOException e) {
                // this really shouldn't happen
                e.printStackTrace();
            }
        }

        // create the tuples
        for (int i=0; i<tuples.length; i++) {

            // empty slot
            if (!isSlotUsed(i)) {
                for (int j=0; j<td.getSize(); j++) {
                    try {
                        dos.writeByte(0);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }
                continue;
            }

            // non-empty slot
            for (int j=0; j<td.numFields(); j++) {
                Field f = tuples[i].getField(j);
                try {
                    f.serialize(dos);
                
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        // padding
        int zerolen = BufferPool.getPageSize() - (header.length + td.getSize() * tuples.length); //- numSlots * td.getSize();
        byte[] zeroes = new byte[zerolen];
        try {
            dos.write(zeroes, 0, zerolen);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            dos.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return baos.toByteArray();
    }

    /**
     * Static method to generate a byte array corresponding to an empty
     * HeapPage.
     * Used to add new, empty pages to the file. Passing the results of
     * this method to the HeapPage constructor will create a HeapPage with
     * no valid tuples in it.
     *
     * @return The returned ByteArray.
     */
    public static byte[] createEmptyPageData() {
        int len = BufferPool.getPageSize();
        return new byte[len]; //all 0
    }

    /**
     * Delete the specified tuple from the page; the corresponding header bit should be updated to reflect
     *   that it is no longer stored on any page.
     * @throws DbException if this tuple is not on this page, or tuple slot is
     *         already empty.
     * @param t The tuple to delete
     */
    public void deleteTuple(Tuple t) throws DbException {
        // some code goes here
        // not necessary for lab1
        // Rid 由pageId 和 TupleId 共同构成 表述元组存在磁盘的位置
        int tid=t.getRecordId().getTupleNumber();
        PageId tmppid=t.getRecordId().getPageId();
        if(!tmppid.equals(this.pid)||tuples[tid]==null||!isSlotUsed(tid))     //顺序不可颠倒
        {
            throw new DbException("tuple not exist");
        }
        else {
            markSlotUsed(tid,false);
            tuples[tid]=null;
        }
    }

    /**
     * Adds the specified tuple to the page;  the tuple should be updated to reflect
     *  that it is now stored on this page.
     * @throws DbException if the page is full (no empty slots) or tupledesc
     *         is mismatch.
     * @param t The tuple to add.
     */
    public void insertTuple(Tuple t) throws DbException {
        // some code goes here
        // not necessary for lab1
        TupleDesc tmpTd=t.getTupleDesc();
        int nume=getNumEmptySlots();
        if(!tmpTd.equals(this.td)||nume==0)
        {
            throw new DbException("Td not match or This page is already full");
        }
        else {
            for(int i=0;i<getNumTuples();i++)
            {
                if(!isSlotUsed(i))
                {
                    tuples[i]=t;
                    t.setRecordId(new RecordId(this.pid,i));
                    markSlotUsed(i,true);
                    break;
                }
            }
        }
    }

    /**
     * Marks this page as dirty/not dirty and record that transaction
     * that did the dirtying
     */
    public void markDirty(boolean dirty, TransactionId tid) {
        // some code goes here
	// not necessary for lab1
        this.isDirty=dirty;
        this.tid=tid;

    }

    /**
     * Returns the tid of the transaction that last dirtied this page, or null if the page is not dirty
     */
    public TransactionId isDirty() {
        // some code goes here
	// Not necessary for lab1
        if(isDirty)
            return this.tid;
        return null;      
    }



    /**
     * Returns true if associated slot on this page is filled.
     */
    /**
     * bitmap中，low bits代表了先填入的slots状态。
     * 因此，第一个headerByte的最小bit代表了第一个slot是否使用，
     * 第二小的bit代表了第二个slot是否使用。
     * 同样，最大headerByte的一些高位可能不与slot存在映射关系，
     * 只是满足headerBytes的ceiling。
     *
     * */
    //大端存址！[11111111 00000011]
    public boolean isSlotUsed(int i) {
        // some code goes here

        int tarHdr=header[i/8];
        int fram=i%8;
        fram=(tarHdr>>fram)&1;
        return (fram==1);
    }
    /**
     * Returns the number of empty slots on this page.
     */
    public int getNumEmptySlots() {
        // some code goes here
        int count=0;
        for(int j=0;j<numSlots;j++)
        {
            if(!isSlotUsed(j))
                count++;
        }
        return count;
    }
    /**
     * Abstraction to fill or clear a slot on this page.
     */
    //动 header
    private void markSlotUsed(int i, boolean value) {
        // some code goes here
        // not necessary for lab1
        int tarHdr=i/8;
        byte uzhd=header[tarHdr];
        int fram=i%8;
        byte mark=(byte)(1<<fram);
        if(value)
        {
            header[tarHdr]=(byte)(uzhd|mark);
        }
        else
        {
            header[tarHdr]=(byte)(uzhd&(~mark));
        }
    }//ok

    /**
     * @return an iterator over all tuples on this page (calling remove on this iterator throws an UnsupportedOperationException)
     * (note that this iterator shouldn't return tuples in empty slots!)
     */
    public Iterator<Tuple> iterator() {
        // some code goes here
        return new ItrTup();
    }
    private class ItrTup implements Iterator<Tuple>
    {
        int count=0,pos=0;
        int avaTup=getNumTuples()-getNumEmptySlots();
        public boolean hasNext()
        {
            if(count<avaTup)
                return true;
            return false;
        }
        public Tuple next()
        {
            if(!hasNext())
                throw new NoSuchElementException();
            while(!isSlotUsed(pos))
            {
                pos++;
            }
            count++;
            return tuples[pos++];
        }
    }

}

