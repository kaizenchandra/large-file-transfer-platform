package com.example.drive;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MediaProcessorsTest {
 @Test void pngThumbnailHasBoundedDimensions() throws Exception {
  var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(800,400,BufferedImage.TYPE_INT_RGB),"png",bytes);
  var store=mock(ObjectStore.class);when(store.open("key")).thenReturn(new ByteArrayInputStream(bytes.toByteArray()));
  var outputs=MediaProcessors.process(store,"key",bytes.size());assertEquals("thumbnail",outputs.getFirst().variant());
  var thumb=ImageIO.read(new ByteArrayInputStream(outputs.getFirst().bytes()));assertEquals(256,thumb.getWidth());assertEquals(128,thumb.getHeight());
 }
 @Test void documentMetadataCountsUnicodeAndLines() throws Exception {
  var store=mock(ObjectStore.class);byte[] input="hello\nworld".getBytes();when(store.open("key")).thenReturn(new ByteArrayInputStream(input));
  var output=MediaProcessors.process(store,"key",input.length).getFirst();assertEquals("document-metadata",output.variant());assertTrue(new String(output.bytes()).contains("\"characters\":11"));
 }
 @Test void oversizedInputsNeverOpenStorage() throws Exception {
  var store=mock(ObjectStore.class);assertTrue(MediaProcessors.process(store,"key",33L*1024*1024).isEmpty());verifyNoInteractions(store);
 }
 @Test void archiveExpansionIsBounded() throws Exception {
  var bytes=new ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(bytes)){for(int i=0;i<1001;i++){zip.putNextEntry(new java.util.zip.ZipEntry("entry"+i));zip.closeEntry();}}
  var store=mock(ObjectStore.class);when(store.open("key")).thenReturn(new ByteArrayInputStream(bytes.toByteArray()));assertThrows(IOException.class,()->MediaProcessors.process(store,"key",bytes.size()));
 }
}
