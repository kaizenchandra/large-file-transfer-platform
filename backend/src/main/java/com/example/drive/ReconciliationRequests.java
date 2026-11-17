package com.example.drive;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
@Component
class ReconciliationRequests {
 final Drive d;final Reconciler reconciler;
 ReconciliationRequests(Drive d,Reconciler reconciler){this.d=d;this.reconciler=reconciler;}
 @Scheduled(fixedDelay=5000) void tick(){
  var jobs=d.db.queryForList("UPDATE reconciliation_requests SET state='RUNNING',attempts=attempts+1,fence=fence+1,lease_until=now()+interval '5 minutes' WHERE id=(SELECT id FROM reconciliation_requests WHERE (state='PENDING' OR (state='RUNNING' AND lease_until<now())) AND attempts<3 ORDER BY created FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING *");
  for(var j:jobs)try{reconciler.reconcileTenant(j.get("tenant").toString());d.db.update("UPDATE reconciliation_requests SET state='SUCCEEDED',lease_until=NULL WHERE id=? AND fence=? AND lease_until>now()",j.get("id"),j.get("fence"));}catch(Exception failed){d.db.update("UPDATE reconciliation_requests SET state=CASE WHEN attempts>=3 THEN 'FAILED' ELSE 'PENDING' END,lease_until=NULL WHERE id=? AND fence=?",j.get("id"),j.get("fence"));}
  d.db.update("UPDATE reconciliation_requests SET state='FAILED' WHERE state='RUNNING' AND attempts>=3 AND lease_until<now()");
  d.db.update("DELETE FROM api_rate_windows WHERE window_start<now()-interval '1 day'");
 }
}
