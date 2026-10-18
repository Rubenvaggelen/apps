package com.theone.tv;
import java.io.*;import java.util.*;
final class CatalogCodec {
 private static final int MAGIC=0x544f4301,MAX=300000;
 static void write(OutputStream stream,List<CatalogEntry> entries)throws IOException{DataOutputStream out=new DataOutputStream(new BufferedOutputStream(stream));out.writeInt(MAGIC);out.writeInt(entries.size());for(CatalogEntry e:entries){out.writeInt(e.id);out.writeUTF(e.title);out.writeUTF(e.extension);out.writeUTF(e.poster);out.writeUTF(e.category);out.writeUTF(e.type);out.writeLong(e.added);out.writeUTF(e.movieId);}out.flush();}
 static List<CatalogEntry> read(InputStream stream,String type)throws IOException{DataInputStream in=new DataInputStream(new BufferedInputStream(stream));if(in.readInt()!=MAGIC)throw new IOException("Invalid catalog");int count=in.readInt();if(count<0||count>MAX)throw new IOException("Invalid catalog");List<CatalogEntry> result=new ArrayList<>(count);for(int i=0;i<count;i++){int id=in.readInt();String title=in.readUTF(),ext=in.readUTF(),poster=in.readUTF(),category=in.readUTF(),entryType=in.readUTF();long added=in.readLong();String tmdb=in.readUTF();if(id<=0||title.isEmpty()||!entryType.equals(type))throw new IOException("Invalid catalog");result.add(new CatalogEntry(id,title,ext,poster,category,entryType,added,tmdb));}return result;}
}
