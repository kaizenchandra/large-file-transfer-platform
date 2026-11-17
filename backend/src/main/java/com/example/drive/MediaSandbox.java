package com.example.drive;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
import java.util.List;

final class MediaSandbox {
 static List<MediaProcessors.Output> process(ObjectStore objects,String key,long size,String endpoint,String token)throws Exception {
  if(endpoint.isBlank()||size>64L*1024*1024)return List.of();
  byte[] prefix;try(var in=objects.open(key)){prefix=in.readNBytes(12);}
  String kind=prefix.length>=5&&new String(prefix,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")?"pdf":prefix.length>=12&&prefix[4]=='f'&&prefix[5]=='t'&&prefix[6]=='y'&&prefix[7]=='p'?"video":null;
  if(kind==null)return List.of();
  var outputs=new java.util.ArrayList<MediaProcessors.Output>();
  outputs.add(request(objects,key,size,endpoint,token,kind));
  if(kind.equals("video"))outputs.add(request(objects,key,size,endpoint,token,"transcode"));
  return outputs;
 }
 static MediaProcessors.Output request(ObjectStore objects,String key,long size,String endpoint,String token,String kind)throws Exception {
  var body=HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(()->objects.open(key)),size);
  var request=HttpRequest.newBuilder(URI.create(endpoint+"/"+kind)).timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+token).POST(body).build();
  try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()){
   var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
   try(var input=response.body()){
    if(response.statusCode()!=200)throw new IOException("Sandbox rejected processing");
    byte[] output=input.readNBytes(8*1024*1024+1);
    if(output.length>8*1024*1024||output.length<8)throw new IOException("Sandbox output invalid");
    if(kind.equals("transcode")){
     if(output.length<12||output[4]!='f'||output[5]!='t'||output[6]!='y'||output[7]!='p')throw new IOException("Invalid transcoded MP4");
     return new MediaProcessors.Output("video-transcoded","video/mp4",output);
    }
    if(output[0]!=(byte)137||output[1]!=80)throw new IOException("Sandbox PNG invalid");
    try(var png=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(output))){var readers=javax.imageio.ImageIO.getImageReaders(png);if(!readers.hasNext())throw new IOException("Invalid preview");var reader=readers.next();try{reader.setInput(png);if(reader.getWidth(0)>1024||reader.getHeight(0)>1024)throw new IOException("Preview dimensions exceed policy");}finally{reader.dispose();}}
    return new MediaProcessors.Output(kind+"-preview","image/png",output);
   }
  }
 }
}
