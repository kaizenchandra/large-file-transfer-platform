package com.example.drive;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.util.*;
import java.time.*;
import java.security.MessageDigest;

/** Database-leased, bounded, version-specific inspection. Reports are never promoted across versions. */
@Component
public class Processing {
 final Drive d;
 Processing(Drive d){this.d=d;}
 @Scheduled(fixedDelay=2000)
 public void tick(){
  var claimed=d.tx.execute(tx->{var rows=d.db.queryForList("""
   UPDATE processing_jobs SET state='RUNNING',fence=fence+1,attempts=attempts+1,lease_until=now()+interval '60 seconds'
   WHERE id=(SELECT id FROM processing_jobs WHERE
    ((state IN ('PENDING','FAILED_RETRYABLE') AND available_at<=now()) OR (state='RUNNING' AND lease_until<now()))
    AND attempts<5 ORDER BY available_at FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING *
   """);for(var row:rows)d.db.update("INSERT INTO processing_attempts(job,fence) VALUES(?,?)",row.get("id"),row.get("fence"));return rows;});
  d.db.update("UPDATE processing_jobs SET state='FAILED_TERMINAL' WHERE state='RUNNING' AND lease_until<now() AND attempts>=5");
  if(claimed==null||claimed.isEmpty())return;
  var j=claimed.getFirst();Object id=j.get("id"),fence=j.get("fence");
  try(var heartbeat=new ProcessingHeartbeat(d.db,id,fence)){
   var v=d.one("SELECT * FROM file_versions WHERE id=?",j.get("source"));
   var hash=MessageDigest.getInstance("SHA-256");long total=0;byte[] prefix=new byte[32];int prefixSize=0;
   long renewal=System.nanoTime();
   try(var input=d.objects.open(v.get("object_key").toString())){byte[] buffer=new byte[131072];int n;
    while((n=input.read(buffer))!=-1){hash.update(buffer,0,n);total+=n;if(prefixSize<32){int take=Math.min(32-prefixSize,n);System.arraycopy(buffer,0,prefix,prefixSize,take);prefixSize+=take;}
     if(System.nanoTime()-renewal>Duration.ofSeconds(15).toNanos()){
      Rules.require(d.db.update("UPDATE processing_jobs SET lease_until=now()+interval '60 seconds' WHERE id=? AND fence=? AND state='RUNNING' AND lease_until>now()",id,fence)==1,409,"Lease lost");renewal=System.nanoTime();}
    }
   }
   String digest=Base64.getEncoder().encodeToString(hash.digest());
   Rules.require(total==d.num(v.get("size"))&&digest.equals(v.get("sha256")),422,"Integrity verification failed");
   String type=prefixSize>=8&&prefix[0]==(byte)137&&prefix[1]==80&&prefix[2]==78&&prefix[3]==71?"image/png":prefixSize>=5&&new String(prefix,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")?"application/pdf":"application/octet-stream";
   
   var outputs=new java.util.ArrayList<>(MediaProcessors.process(d.objects,v.get("object_key").toString(),total));
   outputs.addAll(MediaSandbox.process(d.objects,v.get("object_key").toString(),total,d.mediaEndpoint,d.mediaToken));
   String report=d.json.writeValueAsString(Map.of("processor","stream-inspector-v1","bytes",total,"sha256",digest,"detectedType",type,"sourceVersion",j.get("source").toString(),"artifactVariants",outputs.stream().map(MediaProcessors.Output::variant).toList(),"mediaPolicy","media-pipeline-v2: input 64 MiB, output 8 MiB, video 120 seconds"));
   for(var output:outputs){
    String key="artifacts/"+id+"/"+fence+"/"+output.variant();
    Rules.require(d.db.update("INSERT INTO garbage_objects(object_key,ns,processing_job) SELECT ?,ns,id FROM processing_jobs WHERE id=? AND fence=? AND state='RUNNING' AND lease_until>now() ON CONFLICT(object_key) DO NOTHING",key,id,fence)==1,409,"Artifact lease lost");
    d.objects.put(key,output.bytes(),output.mime());
    Boolean promoted=d.tx.execute(tx->{
     d.one("SELECT id FROM namespaces WHERE id=? FOR UPDATE",j.get("ns"));
     var owner=d.db.queryForList("SELECT id FROM processing_jobs WHERE id=? AND fence=? AND state='RUNNING' AND lease_until>now() FOR UPDATE",id,fence);
     if(owner.isEmpty())return false;
     d.db.update("INSERT INTO processed_artifacts(job,variant,source,object_key,size,sha256,processor,fence) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(job,variant) DO UPDATE SET object_key=excluded.object_key,size=excluded.size,sha256=excluded.sha256,processor=excluded.processor,fence=excluded.fence",id,output.variant(),j.get("source"),key,output.bytes().length,Rules.hash(output.bytes()),"media-pipeline-v2",fence);return true;
    });
    if(!Boolean.TRUE.equals(promoted))d.objects.delete(key);
   }
   d.tx.execute(tx->{int promoted=d.db.update("UPDATE processing_jobs SET state='SUCCEEDED',report=?::jsonb,lease_until=NULL WHERE id=? AND fence=? AND state='RUNNING' AND lease_until>now()",report,id,fence);d.db.update("UPDATE processing_attempts SET outcome=?,finished=now() WHERE job=? AND fence=?",promoted==1?"SUCCEEDED":"STALE",id,fence);return null;});
  }catch(Exception e){d.db.update("UPDATE processing_attempts SET outcome='FAILED',finished=now() WHERE job=? AND fence=?",id,fence);org.slf4j.LoggerFactory.getLogger(Processing.class).warn("Media attempt failed: {} / {}",e.getClass().getSimpleName(),e.getCause()==null?"none":e.getCause().getClass().getSimpleName());d.db.update("UPDATE processing_jobs SET state=CASE WHEN attempts>=5 THEN 'FAILED_TERMINAL' ELSE 'FAILED_RETRYABLE' END,error='Inspection failed',available_at=now()+interval '30 seconds',lease_until=NULL WHERE id=? AND fence=? AND state='RUNNING' AND lease_until>now()",id,fence);}
  d.db.update("UPDATE processing_jobs SET state='FAILED_TERMINAL' WHERE state='RUNNING' AND lease_until<now() AND attempts>=5");
 }
}

@RestController
@RequestMapping("/api/v1")
class ProcessingApi {
 final Drive d; ProcessingApi(Drive d){this.d=d;}
 @GetMapping("/files/{id}/processing") Object jobs(Authentication a,@PathVariable UUID id){var actor=d.actor(a);var ns=d.home(actor);d.read(actor,ns,id);return d.db.queryForList("SELECT id,source,state,attempts,report,error FROM processing_jobs WHERE ns=? AND node=? ORDER BY created DESC LIMIT 100",ns,id);}
 @GetMapping("/jobs/{id}") Object job(Authentication a,@PathVariable UUID id){var actor=d.actor(a);var ns=d.home(actor);return d.one("SELECT id,source,state,attempts,report,error FROM processing_jobs WHERE ns=? AND id=?",ns,id);}
 @PostMapping("/jobs/{id}/cancel") Object cancel(Authentication a,@PathVariable UUID id){var ns=d.home(d.actor(a));return Map.of("cancelled",d.db.update("UPDATE processing_jobs SET state='CANCELLED',fence=fence+1 WHERE id=? AND ns=? AND state IN ('PENDING','RUNNING','FAILED_RETRYABLE')",id,ns));}
 @GetMapping("/files/{id}/artifacts") Object artifacts(Authentication a,@PathVariable UUID id){var actor=d.actor(a);var ns=d.home(actor);var node=d.read(actor,ns,id);d.authorizedVersion(actor,ns,id,null);return d.db.queryForList("SELECT a.variant,a.size,a.sha256,a.processor FROM processed_artifacts a JOIN processing_jobs j ON j.id=a.job WHERE a.source=? AND j.state='SUCCEEDED' AND a.fence=j.fence",node.get("current_version"));}
 @GetMapping("/files/{id}/artifacts/{variant}/download") Object artifact(Authentication a,@PathVariable UUID id,@PathVariable String variant){var actor=d.actor(a);var ns=d.home(actor);return d.locked(actor,ns,()->{var version=d.authorizedVersion(actor,ns,id,null);var artifact=d.one("SELECT a.* FROM processed_artifacts a JOIN processing_jobs j ON j.id=a.job WHERE a.source=? AND a.variant=? AND j.state='SUCCEEDED' AND a.fence=j.fence",version.get("id"),variant);d.db.update("INSERT INTO audit_records(ns,actor,action,entity) VALUES(?,?,'ARTIFACT_CREDENTIAL',?)",ns,actor.subject(),id);return Map.of("url",d.objects.download(artifact.get("object_key").toString(),variant),"sha256",artifact.get("sha256"),"size",artifact.get("size"));});}
 @GetMapping("/jobs/{id}/attempts") Object attempts(Authentication a,@PathVariable UUID id){var ns=d.home(d.actor(a));d.one("SELECT id FROM processing_jobs WHERE id=? AND ns=?",id,ns);return d.db.queryForList("SELECT fence,started,finished,outcome FROM processing_attempts WHERE job=? ORDER BY fence DESC LIMIT 100",id);}
}
