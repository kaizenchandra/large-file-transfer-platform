package com.example.drive;

import javax.imageio.ImageIO;
import java.io.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;

/** No subprocesses, document scripts or network access. Explicit byte, pixel and archive expansion budgets. */
final class MediaProcessors {
 record Output(String variant,String mime,byte[] bytes){}
 static List<Output> process(ObjectStore store,String key,long size) throws IOException {
  if(size>32L*1024*1024)return List.of();
  byte[] bytes;
  try(var in=store.open(key)){bytes=in.readNBytes(32*1024*1024+1);}
  if(bytes.length>32*1024*1024)throw new IOException("Processor input limit");
  var outputs=new ArrayList<Output>();
  boolean image=bytes.length>=8&&bytes[0]==(byte)137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71;
  if(image){
   try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
    var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Unsupported PNG");var reader=readers.next();
    try{reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);if(width<=0||height<=0||(long)width*height>16_000_000)throw new IOException("Pixel budget exceeded");
     var params=reader.getDefaultReadParam();int sample=Math.max(1,Math.max(width,height)/1024);params.setSourceSubsampling(sample,sample,0,0);var source=reader.read(0,params);
     double scale=Math.min(1,256.0/Math.max(source.getWidth(),source.getHeight()));var thumb=new BufferedImage(Math.max(1,(int)(source.getWidth()*scale)),Math.max(1,(int)(source.getHeight()*scale)),BufferedImage.TYPE_INT_ARGB);
     var graphics=thumb.createGraphics();try{graphics.drawImage(source,0,0,thumb.getWidth(),thumb.getHeight(),null);}finally{graphics.dispose();}
     var out=new ByteArrayOutputStream();if(!ImageIO.write(thumb,"png",out))throw new IOException("PNG writer unavailable");outputs.add(new Output("thumbnail","image/png",out.toByteArray()));
    }finally{reader.dispose();}
   }
  }else if(bytes.length>=4&&bytes[0]==80&&bytes[1]==75&&bytes[2]==3&&bytes[3]==4){
   long expanded=0;int entries=0;byte[] buffer=new byte[8192];
   try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null){if(++entries>1000)throw new IOException("Archive entry budget");int n;while((n=zip.read(buffer))!=-1){expanded+=n;if(expanded>64L*1024*1024)throw new IOException("Archive expansion budget");}}}
   outputs.add(new Output("archive-report","application/json",("{\"entries\":"+entries+",\"expandedBytes\":"+expanded+"}").getBytes(StandardCharsets.UTF_8)));
  }else{
   try{var text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));long lines=0;boolean safe=true;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='\n')lines++;if(c<32&&c!='\n'&&c!='\r'&&c!='\t')safe=false;}
    if(safe)outputs.add(new Output("document-metadata","application/json",("{\"encoding\":\"UTF-8\",\"characters\":"+text.length()+",\"newlines\":"+lines+"}").getBytes(StandardCharsets.UTF_8)));
   }catch(java.nio.charset.CharacterCodingException binary){/* Unsupported binary format: no parser is invoked. */}
  }
  return outputs;
 }
}
