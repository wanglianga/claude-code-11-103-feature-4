package com.medwaste.manifest;

import com.medwaste.auth.AppUser;
import com.medwaste.common.ApiException;
import com.medwaste.common.Codes;
import com.medwaste.common.Db;
import com.medwaste.common.Views;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 封托装载：声明完整箱清单 + 实际扫码清单，用于合称完整性判定。 */
@Service
public class PalletService {
    private final Db db;
    private final ManifestService manifests;

    public PalletService(Db db, ManifestService manifests) {
        this.db = db;
        this.manifests = manifests;
    }

    public Map<String, Object> loadDetail(long id) {
        return Views.of(db.one("SELECT * FROM pallet_loads WHERE id=?", id));
    }

    public Map<String, Object> loadByCode(String code) {
        return Views.of(db.one("SELECT * FROM pallet_loads WHERE code=?", code));
    }

    @Transactional
    public Map<String, Object> createLoad(String manifestCode, String palletCode,
                                          List<String> boxQrs, AppUser user) {
        Map<String, Object> m = db.one("SELECT * FROM manifests WHERE code=?", manifestCode);
        if (!List.of("DRAFT", "SUBMITTED").contains(m.get("status"))) {
            throw ApiException.conflict("MANIFEST_LOCKED", "交接单已交接，不能再组托");
        }
        Map<String, Object> pallet = db.maybeOne("SELECT * FROM pallets WHERE code=?", palletCode.toUpperCase())
                .orElseThrow(() -> ApiException.notFound("PALLET_NOT_FOUND", "不存在的托盘: " + palletCode));

        List<Long> ids = new ArrayList<>();
        List<String> qrs = new ArrayList<>();
        for (String qr : boxQrs) {
            Map<String, Object> r = manifests.resolveScan(qr);
            if (!"SEALED".equals(r.get("status"))) {
                throw ApiException.conflict("BOX_NOT_SEALED", "组托清单中的 " + r.get("qrCode") + " 尚未封箱");
            }
            ids.add(((Number) r.get("id")).longValue());
            qrs.add((String) r.get("qrCode"));
        }
        if (ids.isEmpty()) throw ApiException.badRequest("EMPTY_PALLET_LIST", "封托清单至少包含一箱");

        String code = Codes.next("PL");
        long id = db.insert("""
                INSERT INTO pallet_loads(code,pallet_id,pallet_code,tare_pallet_kg,manifest_id,status,
                  declared_round_ids,declared_qrs,created_by_name)
                VALUES (?,?,?,?,?, 'DRAFT', ?::jsonb, ?::jsonb, ?)
                """, code, pallet.get("id"), pallet.get("code"), pallet.get("tare_kg"), m.get("id"),
                com.medwaste.common.Jsonx.write(ids), com.medwaste.common.Jsonx.write(qrs),
                user == null ? null : user.displayName());
        return loadDetail(id);
    }

    @Transactional
    public Map<String, Object> scanBox(long loadId, String boxQr) {
        Map<String, Object> load = db.one("SELECT * FROM pallet_loads WHERE id=? FOR UPDATE", loadId);
        Map<String, Object> round = manifests.resolveScan(boxQr);
        @SuppressWarnings("unchecked")
        List<Object> declared = (List<Object>) Views.of(load).get("declaredRoundIds");
        long roundId = ((Number) round.get("id")).longValue();
        if (declared.stream().noneMatch(o -> ((Number) o).longValue() == roundId)) {
            throw ApiException.badRequest("BOX_NOT_DECLARED",
                    "箱 " + round.get("qrCode") + " 不在该托声明的封箱清单内，不能计入本托扫码");
        }
        @SuppressWarnings("unchecked")
        List<Object> scanned = new ArrayList<>((List<Object>) Views.of(load).getOrDefault("scannedRoundIds", List.of()));
        if (scanned.stream().noneMatch(o -> ((Number) o).longValue() == roundId)) scanned.add(roundId);

        @SuppressWarnings("unchecked")
        List<String> scannedQrs = new ArrayList<>((List<String>) Views.of(load).getOrDefault("scannedQrs", List.of()));
        if (!scannedQrs.contains(round.get("qrCode"))) scannedQrs.add((String) round.get("qrCode"));

        db.update("UPDATE pallet_loads SET scanned_round_ids=?::jsonb, scanned_qrs=?::jsonb WHERE id=?",
                com.medwaste.common.Jsonx.write(scanned), com.medwaste.common.Jsonx.write(scannedQrs), loadId);
        return loadDetail(loadId);
    }
}
