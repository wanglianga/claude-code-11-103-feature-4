package com.medwaste.custody;

import com.medwaste.common.Db;
import org.springframework.stereotype.Service;

/** 保管责任事件只追加，不修改不删除——真实交接事实留痕。 */
@Service
public class CustodyService {
    private final Db db;

    public CustodyService(Db db) {
        this.db = db;
    }

    public void event(String targetType, String targetCode, String action,
                      String fromParty, String toParty, Long orgId,
                      String actorName, String detail) {
        db.update("""
                INSERT INTO custody_events(target_type,target_code,action,from_party,to_party,org_id,actor_name,detail)
                VALUES (?,?,?,?,?,?,?,?)
                """, targetType, targetCode, action, fromParty, toParty, orgId, actorName, detail);
    }
}
