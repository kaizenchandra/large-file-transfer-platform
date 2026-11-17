package com.example.drive;
import java.io.*;
import java.util.Arrays;

/** Signature screening only: never grants whole-file clean approval. */
final class SegmentedScan {
 static MalwareGate.Decision inspect(InputStream source,long size,MalwareScanner scanner,int window,int overlap)throws IOException {
  if(window<=overlap||overlap<0)throw new IllegalArgumentException("Invalid segment bounds");
  byte[] bytes=new byte[window];int carried=0,segments=0;long covered=0;String engine="unavailable";
  while(covered<size){
   int wanted=(int)Math.min(window-carried,size-covered),read=0;
   while(read<wanted){int n=source.read(bytes,carried+read,wanted-read);if(n<=0)throw new IOException("Truncated large scan input");read+=n;}
   covered+=read;int length=carried+read;segments++;
   var verdict=scanner.scan(new ByteArrayInputStream(bytes,0,length));
   engine=verdict.engine();
   if(verdict.blocked())return new MalwareGate.Decision("BLOCKED",verdict.engine(),covered,segments,"segmented-signature-only");
   carried=Math.min(overlap,length);System.arraycopy(bytes,length-carried,bytes,0,carried);
  }
  if(source.read()!=-1)throw new IOException("Large scan size mismatch");
  return new MalwareGate.Decision("REVIEW_REQUIRED",engine+"; segmented signatures; full-file format inspection unsupported",covered,segments,"segmented-signature-only");
 }
}
