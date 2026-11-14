package io.stamethyst.deltabench;

import java.io.*;
import java.util.Random;

/** Fixed-size buffers even for larger inputs. Seed/bytes match Corpus for aligned sizes. */
public final class FileCorpus {
    public static void prepare(File base, File target, int size, String fixture) throws IOException {
        if(size<1024 || size>64*1024*1024) throw new IOException("File corpus size limit");
        random(base,size,Corpus.SEED);
        if(fixture.equals("unrelated")){ random(target,size,Corpus.SEED+1); return; }
        try(RandomAccessFile in=new RandomAccessFile(base,"r"); FileOutputStream out=new FileOutputStream(target)){
            if(fixture.equals("insert-middle")){
                range(in,out,0,size/2,false);byte[] insert=new byte[37];java.util.Arrays.fill(insert,(byte)0x7b);out.write(insert);
                range(in,out,size/2,size-size/2,false);
            }else if(fixture.equals("move-quarter")){
                range(in,out,size/4,size-size/4,false);range(in,out,0,size/4,false);
            }else if(fixture.equals("delete-middle")){
                range(in,out,0,size/2,false);range(in,out,size/2+37,size-size/2-37,false);
            }else if(fixture.equals("sparse") || fixture.equals("identical")){
                range(in,out,0,size,fixture.equals("sparse"));
            }else throw new IOException("Unsupported file fixture");
            out.getFD().sync();
        }
    }
    private static void random(File file,int size,long seed)throws IOException{
        Random random=new Random(seed);byte[] buffer=new byte[65536];
        try(FileOutputStream out=new FileOutputStream(file)){
            int left=size;while(left>0){random.nextBytes(buffer);int n=Math.min(left,buffer.length);out.write(buffer,0,n);left-=n;}
            out.getFD().sync();
        }
    }
    private static void range(RandomAccessFile in,OutputStream out,long offset,int size,boolean sparse)throws IOException{
        in.seek(offset);byte[] buffer=new byte[65536];int done=0;
        while(done<size){int n=Math.min(buffer.length,size-done);in.readFully(buffer,0,n);
            if(sparse)for(int i=0;i<n;i++)if((offset+done+i)%4096==0)buffer[i]^=0x55;
            out.write(buffer,0,n);done+=n;}
    }
    private FileCorpus(){}
}
