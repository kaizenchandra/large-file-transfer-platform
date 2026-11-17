package com.example.drive;
import java.io.*;import java.nio.charset.StandardCharsets;import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SegmentedScanTest {
 @Test void allBytesCoveredButNoWholeFileClearance()throws Exception{
  byte[] data=new byte[100];var result=SegmentedScan.inspect(new ByteArrayInputStream(data),data.length,s->new MalwareScanner.Verdict(false,"test"),32,8);
  assertEquals("REVIEW_REQUIRED",result.verdict());assertEquals(100,result.coveredBytes());assertEquals(4,result.segments());
 }
 @Test void boundarySignatureDetected()throws Exception{
  byte[] data=new byte[80];byte[] signature="SYNTHETIC".getBytes();System.arraycopy(signature,0,data,29,signature.length);
  var result=SegmentedScan.inspect(new ByteArrayInputStream(data),data.length,s->new MalwareScanner.Verdict(new String(s.readAllBytes(),StandardCharsets.US_ASCII).contains("SYNTHETIC"),"test"),32,16);
  assertEquals("BLOCKED",result.verdict());
 }
 @Test void wrongLengthFailsClosed(){assertThrows(IOException.class,()->SegmentedScan.inspect(new ByteArrayInputStream(new byte[10]),12,s->new MalwareScanner.Verdict(false,"test"),8,2));}
}
