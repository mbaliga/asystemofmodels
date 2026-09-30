"""World builders for gen_vectors.py: the JSON shapes the conformance runner's router families decode (LAB_SPEC 6.1, 6.4)."""
import copy

A = "a1" * 32
B = "b2" * 32
C = "c3" * 32
NOW = 1000500
WALL = 1790000000000


def F(sha=A, model="qwen3-8b", quant="Q4_K_M", bytes=5027784832, rank=None, kind="CHAT", ctx=32768):
    return dict(modelId=model, fileSha256=sha, quant=quant, fileBytes=bytes, catalogueRank=rank, kind=kind, contextTokens=ctx)


def PR(prefill=60000, decode=12000, ttft0=300, steady=12000, onset=None, power=None, kv=100000, peak=6000000000, flags=(), curve=None):
    return dict(decodeAt=curve or [[512, decode]], prefillMilliTokPerSec=prefill, ttft0Ms=ttft0, steadyMilliTokPerSec=steady, throttleOnsetMs=onset,
                powerMilliW=power, kvBytesPerToken=kv, peakProcessBytes=peak, flags=list(flags))


def ST(seq=10, age=0, fsm="SERVING", src="ac", chg=False, band=None, tb=0, gov="RUN", backend="metal", held=(A,), qb=0):
    return dict(seq=seq, sampledAgeMs=age, fsm=fsm, powerSource=src, charging=chg, batteryBand=band, thermalBand=tb, governor=gov, backend=backend,
                commit="0123abc", confVersion="0.2.0", held=list(held), queueBucket=qb, manifestSeq=None, manifestDigest=None)


def LK(rtt=10, kbps=100000, path="LAN", metered=False, warm=True, samples=5):
    return dict(rttMs=rtt, kbps=kbps, path=path, metered=metered, sessionWarm=warm, samples=samples)


LIMITS = dict(maxConcurrent=1, maxBodyBytes=8388608, maxTokens=4096, rpm=30, idleUnloadMs=300000)


def ROW(paired=True, route=True, grant=True, charge=False, limits=None):
    return dict(paired=paired, routeEnabled=route, inferGrantedToMe=grant, requireCharging=charge, limits=limits or dict(LIMITS))


def PEER(id, cls="DESKTOP", files=None, backend="metal", prior="default", state="default", rx=1000000, link="default", row=None, breaker=None, last_same=None,
         reservations=0, design=None, session_open=True, goaway=False, max_ctx=None, regressed=False):
    files = files if files is not None else [F()]
    pr = PR() if prior == "default" else prior
    st = ST(backend=backend) if state == "default" else state
    priors = [] if pr is None else [dict(nodeId=id, fileSha256=f["fileSha256"], backend=backend, prior=copy.deepcopy(pr)) for f in files]
    return dict(nodeId=id, nodeTag=id[:8], tier="PEER", deviceClass=cls, peer=row or ROW(), files=files, priors=priors, self=None, state=st,
                stateRxMonoMs=None if st is None else rx, sessionOpen=session_open, goawaySeen=goaway, link=LK() if link == "default" else link,
                breaker=breaker or dict(coolingUntilMonoMs=None, declineBackoffUntilMonoMs=None, halfOpen=False), lastSameFileMonoMs=last_same or {},
                ownReservationsMs=reservations, maxContextTokens=max_ctx, batteryDesignMilliWh=design, stateRegressed=regressed)


def SS(permille=900, chg=False, on_batt=True, thermal=0, gov="RUN", avail=20000000000, loaded=(A,), active=False, busy=0, design=19000, engine=True,
       backend="cpu", queue=0, saver=False):
    return dict(batteryPermille=permille, charging=chg, onBattery=on_batt, saver=saver, batteryDesignMilliWh=design, thermalCode=thermal, governor=gov,
                availBytes=avail, loaded=list(loaded), userActive=active, busyForMs=busy, hasEngine=engine, backend=backend, localQueueMs=queue)


def SELF(id="self-node", cls="PHONE", files=None, prior="default", situation=None):
    files = files if files is not None else [F()]
    sit = situation or SS()
    pr = PR(prefill=30000, decode=5000, ttft0=200, steady=4000, onset=180000, power=5000) if prior == "default" else prior
    priors = [] if pr is None else [dict(nodeId=id, fileSha256=f["fileSha256"], backend=sit["backend"], prior=copy.deepcopy(pr)) for f in files]
    return dict(nodeId=id, nodeTag=id[:8], tier="SELF", deviceClass=cls, peer=None, files=files, priors=priors, self=sit, state=None, stateRxMonoMs=None,
                sessionOpen=False, goawaySeen=False, link=None, breaker=dict(coolingUntilMonoMs=None, declineBackoffUntilMonoMs=None, halfOpen=False),
                lastSameFileMonoMs={}, ownReservationsMs=0, maxContextTokens=None, batteryDesignMilliWh=None, stateRegressed=False)


APP = dict(pkg="app.a", meshAllowed=True, cloudBanned=False, deviceOnly=False, allowMeshOnMetered=False, neverCloudWhenDevicesCanAnswer=False)


def Q(model="auto", header=None, fallback=(), no_train=False, op="chat", stream=True, prompt=500, bytes=2000, cap=300, deadline=120000, embedding=None, app=None):
    return dict(model=model, policyHeader=header, fallback=list(fallback), noTrain=no_train, op=op, stream=stream, promptTokens=prompt, promptBytes=bytes,
                maxTokensCap=cap, deadlineMs=deadline, embeddingIdentity=embedding, app=app or dict(APP))


def CLOUD(keys=(), cooling=None, ewma=None, rates=None):
    return dict(keysPresent=list(keys), coolingUntilWallMs=cooling or {}, ewmaMs=ewma or {}, cloudRates=rates or {})


def WORLD(self_=None, peers=(), q=None, mesh=True, cloud=None, tracker=(), caps=(), penalties=None, app_ewma=None, config=None, now=NOW, wall=WALL):
    return dict(nowMonoMs=now, wallNowMs=wall, meshGlobalOn=mesh, query=q or Q(), self=self_ or SELF(), peers=list(peers), cloud=cloud or CLOUD(),
                tracker=list(tracker), caps=list(caps), penalties=penalties or {}, appEwmaOut=app_ewma or {}, config=config or {})


def TS(ratios=(), recent=(), strikes=0, inherited=False, memory=False, claim_seq=1, accepted_at=None):
    return dict(claimSeq=claim_seq, acceptedAtWallMs=accepted_at, ratios=list(ratios), recent=list(recent), strikes=strikes, inheritedDiscrepant=inherited, memoryDiscrepant=memory)


def TRK(node, sha, backend, state):
    return dict(nodeId=node, fileSha256=sha, backend=backend, state=state)


def dup(x):
    return copy.deepcopy(x)
