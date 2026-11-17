package com.example.drive;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.UUID;

@Component
class CompletionWorker {
    final Drive d;
    final java.util.concurrent.ThreadPoolExecutor pool=new java.util.concurrent.ThreadPoolExecutor(2,2,0,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.SynchronousQueue<>(),Thread.ofPlatform().daemon().name("completion-task-",0).factory());
    @jakarta.annotation.PreDestroy void close(){pool.shutdownNow();}
    CompletionWorker(Drive d){this.d=d;}
    @Scheduled(fixedDelayString="${drive.completion-ms:1000}")
    void tick(){
        var rows=d.db.queryForList("SELECT u.id,u.ns,u.actor,n.tenant FROM upload_sessions u JOIN namespaces n ON n.id=u.ns WHERE u.state='COMPLETING' AND u.retry_at<=now() AND (u.completion_lease IS NULL OR u.completion_lease<now()) AND n.active ORDER BY u.retry_at LIMIT 2");
        for(var row:rows)try{pool.execute(()->process(row));}catch(java.util.concurrent.RejectedExecutionException busy){break;}
    }
    void process(java.util.Map<String,Object> row){
        try{
            d.complete(new Drive.Actor(row.get("tenant").toString(),row.get("actor").toString()),(UUID)row.get("ns"),(UUID)row.get("id"));
        }catch(Exception failure){
            org.slf4j.LoggerFactory.getLogger(CompletionWorker.class).warn("Completion attempt failed: {} / {}",failure.getClass().getSimpleName(),failure.getCause()==null?"none":failure.getCause().getClass().getSimpleName());
            var actor=new Drive.Actor(row.get("tenant").toString(),row.get("actor").toString());
            d.locked(actor,(UUID)row.get("ns"),()->{
                var u=d.upload(actor,(UUID)row.get("ns"),(UUID)row.get("id"));
                if(!u.get("state").equals("COMPLETING") || u.get("completion_lease")!=null)return null;
                boolean permanent=failure instanceof org.springframework.dao.DataIntegrityViolationException || (failure instanceof org.springframework.web.server.ResponseStatusException r && java.util.Set.of(400,403,404,409,412,422).contains(r.getStatusCode().value()));
                if(permanent || d.num(u.get("completion_attempts"))>=8){
                    d.db.update("UPDATE upload_sessions SET state='QUARANTINED',completion_failure_status=? WHERE id=?",failure instanceof org.springframework.dao.DataIntegrityViolationException ? 409 : failure instanceof org.springframework.web.server.ResponseStatusException r ? r.getStatusCode().value() : 503,row.get("id"));
                    d.db.update("UPDATE namespaces SET reserved=reserved-?,quarantined=quarantined+? WHERE id=?",u.get("size"),u.get("size"),row.get("ns"));
                    d.db.update("INSERT INTO audit_records(ns,actor,action,entity) VALUES(?,'system:completion','COMPLETION_REVIEW',?)",row.get("ns"),row.get("id"));
                }else d.db.update("UPDATE upload_sessions SET failures=least(failures+1,30),retry_at=now()+least(3600,5*power(2,least(failures,9)))*interval '1 second' WHERE id=? AND state='COMPLETING'",row.get("id"));
                return null;
            });
        }
    }
}
