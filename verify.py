"""FLPATH-5078 follow-up: verify two correctness defects in the external seekable
ZIP prototype, before and after fixes. Harness rules:
 - unexpected crashes, timeouts, memory failures and residual temp files FAIL;
 - successful non-faulted runs assert complete expected metadata (differential
   against the independent JDK ZipFile reference engine);
 - failure-injection runs assert exact diagnostics AND absence of partial results.
All children: -Xmx32m, process-group kill on deadline, scoped temp cleanup.
Sources of truth: SeekProbe.java/Spill.java in THIS directory (copies of the
prototype originals, then fixed here). Prototype dir stays read-only.
"""
import pathlib, subprocess, json, zipfile, struct, zlib, os, signal, time, sys, tempfile, re
W=pathlib.Path(__file__).resolve().parent
B=W.parent/'pr7-seekable-revision-prjUvQCc'          # original prototype, READ-ONLY
R=W.parent/'flpath-5078-fix'                          # production checkout, READ-ONLY
J='/Users/jgil/.cache/project-lightpath/jdk/jdk-21.0.13+9/Contents/Home/bin/'
S='META-INF/services/demo.Provider'; V=b'demo.DefaultProvider\n'
F=W/'fixtures'; F.mkdir(exist_ok=True)
base=str(R/'target/classes')+':'+(R/'target/safety-isolated-classpath.txt').read_text().strip()
sources=[R/'src/main/java/com/projectlightpath/analysis/java'/('ServiceLoaderDescriptor'+n+'.java')
         for n in ['ArchiveSource','NestedArchiveSource','Source','Reader','Scanner','Authorization']]
sources+=[W/'SeekProbe.java',W/'Spill.java']
(W/'classes').mkdir(exist_ok=True); (W/'no-source').mkdir(exist_ok=True)
subprocess.run([J+'javac','--release','17','-cp',base,'-sourcepath',str(W/'no-source'),
                '-d',str(W/'classes'),*map(str,sources)],check=True,timeout=180)
print('compiled workdir sources sha256:',
      {p.name:__import__('hashlib').sha256(p.read_bytes()).hexdigest()[:16] for p in sources[-2:]},flush=True)

failures=[]; rows=[]
def check(test,cond,detail=''):
    tag='PASS' if cond else 'FAIL'
    print('%s %s %s'%(tag,test,detail if not cond else ''),flush=True)
    if not cond: failures.append((test,detail))

EPAT=re.compile(r'Diagnostic\[code=([^,]*), path=(.*), detail=(.*)\]$')
def parse_E(row):
    out=[]
    for line in row['E']:
        m=EPAT.search(line)
        out.append((m.group(1),m.group(3)) if m else ('?','?'))
    return out

def run(test,p,engine='seek',props=(),others=(),metadata=16<<20,timeout=90):
    owned=pathlib.Path(tempfile.mkdtemp(prefix='child-',dir=W))
    cmd=[J+'java','-Xms16m','-Xmx32m','-Dspike.tempRoot='+str(owned),'-Dspike.root='+str(W),
         *props,'-cp',str(W/'classes')+':'+base,'com.projectlightpath.analysis.java.SeekProbe',
         engine,str(metadata),str(p),*map(str,others)]
    t=time.monotonic(); child=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                                               text=True,start_new_session=True)
    timed=False
    try: out,err=child.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed=True; os.killpg(child.pid,signal.SIGKILL); out,err=child.communicate()
    residual=list(owned.rglob('*'))
    for q in sorted(residual,key=lambda x:len(x.parts),reverse=True):
        q.rmdir() if q.is_dir() else q.unlink()
    owned.rmdir()
    lines=out.splitlines()
    row=dict(test=test,file=str(p),engine=engine,props=list(props),metadata=metadata,
             timeout=timed,code=None if timed else child.returncode,stdout=out,stderr=err,
             residual=len(residual),
             D=[x for x in lines if x.startswith('D\t')],E=[x for x in lines if x.startswith('E\t')],
             R=[x for x in lines if x.startswith('R certainty=')],
             S=[x for x in lines if x.startswith('S ')],T=[x for x in lines if x.startswith('T ')],
             seconds=round(time.monotonic()-t,3))
    rows.append(row)
    # Harness rule 1: crashes, timeouts, memory failures and cleanup fail validation.
    check(test+'/sanity',not timed,'child timed out after %ss'%timeout)
    check(test+'/sanity',row['code']==0,'exit=%s stderr=%s'%(row['code'],err[:400]))
    check(test+'/sanity','OutOfMemoryError' not in err and 'Exception in thread' not in err,
          'memory/crash failure: %s'%err[:400])
    check(test+'/sanity',row['residual']==0,'residual temp paths: %d'%row['residual'])
    sm=dict((kv.split('=',1) for kv in row['S'][0].split()[1:])) if row['S'] else {}
    check(test+'/sanity',sm.get('opened')==sm.get('closed') and sm.get('opened') is not None,
          'opened=%s closed=%s'%(sm.get('opened'),sm.get('closed')))
    row['stats']=sm
    print('  %s engine=%s %.1fs code=%s D=%d E=%d expanded=%s'%(
        pathlib.Path(p).name,engine,row['seconds'],row['code'],len(row['D']),len(row['E']),
        sm.get('expanded')),flush=True)
    return row

def stat(row,key):
    return row['stats'].get(key)

# ---------- fixtures (locally generated, isolated under W/fixtures) ----------
def mk_malformed_after_output():
    # Non-final stored block emits 4096 bytes, then reserved DEFLATE block type.
    data=b'x'*4096
    compressed=b'\x00'+struct.pack('<HH',len(data),65535-len(data))+data+b'\x07'
    name=S.encode(); crc=zlib.crc32(data)
    local=struct.pack('<IHHHHHIIIHH',0x04034b50,20,0,8,0,0,crc,len(compressed),len(data),len(name),0)+name+compressed
    cen=struct.pack('<IHHHHHHIIIHHHHHII',0x02014b50,20,20,0,8,0,0,crc,len(compressed),len(data),len(name),0,0,0,0,0,0)+name
    eocd=struct.pack('<IHHHHIIH',0x06054b50,0,0,1,1,len(cen),len(local),0)
    p=F/'malformed-after-output.jar'; p.write_bytes(local+cen+eocd); return p

def mk_classic_exact(n_ignored,patch_count=None,patch_bytes=None):
    p=F/('classic-exact-%d.jar'%n_ignored if patch_count is None else 'classic-patched.jar')
    with zipfile.ZipFile(p,'w',allowZip64=False) as z:
        for i in range(n_ignored): z.writestr('ignored/%05d'%i,b'')
        z.writestr(S,V)
    if patch_count is not None or patch_bytes is not None:
        a=bytearray(p.read_bytes()); e=len(a)-22
        assert struct.unpack_from('<I',a,e)[0]==0x06054b50
        if patch_count is not None:
            struct.pack_into('<H',a,e+8,patch_count); struct.pack_into('<H',a,e+10,patch_count)
        if patch_bytes is not None: struct.pack_into('<I',a,e+12,patch_bytes)
        p=F/'classic-patched.jar'; p.write_bytes(a)
    return p

def mk_valid(name='valid-first.jar'):
    p=F/name
    with zipfile.ZipFile(p,'w') as z: z.writestr(S,V)
    return p

def be2(n):return struct.pack('>H',n)
def be4(n):return struct.pack('>I',n)
def module(n):
    cp=[]
    def utf(s):
        cp.append(b'\1'+be2(len(s))+s.encode());return len(cp)
    def clas(s):
        i=utf(s);cp.append(b'\7'+be2(i));return len(cp)
    own=clas('module-info'); attr=utf('Module'); mn=utf('test.module');cp.append(b'\x13'+be2(mn));mi=len(cp)
    jn=utf('java.base');cp.append(b'\x13'+be2(jn));ji=len(cp)
    provider=clas('demo/DefaultProvider')
    services=[clas('p%05d/Service'%i) for i in range(n)]
    body=be2(mi)+be2(0)+be2(0)+be2(1)+be2(ji)+be2(0x8000)+be2(0)+be2(0)+be2(0)+be2(0)+be2(n)
    body+=b''.join(be2(s)+be2(1)+be2(provider) for s in services)
    return b'\xca\xfe\xba\xbe'+be2(0)+be2(53)+be2(len(cp)+1)+b''.join(cp)+be2(0x8000)+be2(own)+be2(0)+be2(0)+be2(0)+be2(0)+be2(1)+be2(attr)+be4(len(body))+body

# ---------- defect 1: failed-decompression accounting ----------
def test_defect1():
    p=mk_malformed_after_output(); safe=mk_valid()
    r=run('defect1_budget_escape',p,metadata=4096,others=[p]*4)
    check('defect1_budget_escape/no_partial_results',len(r['D'])==0,'D lines leaked: %d'%len(r['D']))
    det=[d[1] for d in parse_E(r)]
    check('defect1_budget_escape/diagnostics',
          det==['invalid_deflate']+['archive_decompression_limit']*4,'details=%s'%det)
    check('defect1_budget_escape/expanded_charged',stat(r,'expanded')=='4096',
          'expanded=%s (4096 bytes produced before the exception must be charged exactly once)'%stat(r,'expanded'))
    r=run('defect1_certainty_escape',p,metadata=4096,others=[p]*4+[safe],
          props=['-Dspike.certainty=true'])
    check('defect1_certainty_escape/no_partial_results',len(r['D'])==0,
          'later valid archive must not read on the escaped budget; D=%s'%r['D'][:1])
    det=[d[1] for d in parse_E(r)]
    check('defect1_certainty_escape/diagnostics',
          det==['invalid_deflate']+['archive_decompression_limit']*5,'details=%s'%det)
    check('defect1_certainty_escape/expanded_charged',stat(r,'expanded')=='4096',
          'expanded=%s'%stat(r,'expanded'))
    check('defect1_certainty_escape/certainty_line',r['R']==['R certainty=POSSIBLE'],
          'expected certainty=POSSIBLE with diagnostics present, got %s'%r['R'])

# ---------- defect 2: classic 65535 entry-count boundary ----------
def test_defect2():
    p=mk_classic_exact(65534)
    a=run('defect2_classic_65535/seek',p)
    b=run('defect2_classic_65535/reference',p,engine='zip-reference')
    check('defect2_classic_65535/accepted',len(a['E'])==0,'E=%s'%a['E'][:1])
    check('defect2_classic_65535/complete_metadata',
          len(a['D'])==1 and len(b['D'])==1 and a['D']==b['D'],
          'seek D=%s reference D=%s'%(a['D'][:1],b['D'][:1]))
    # Strictness retained: 65534 real entries (65533 ignored + service) with a
    # claimed count of 65535 must be rejected by the directory/count validation.
    q=mk_classic_exact(65533,patch_count=65535)
    r=run('defect2_neg_count_mismatch',q)
    check('defect2_neg_count_mismatch/rejected',len(r['E'])==1 and len(r['D'])==0,
          'E=%s D=%d'%(parse_E(r),len(r['D'])))
    check('defect2_neg_count_mismatch/detail',parse_E(r)==[('seek_incomplete','cen_count_mismatch')],
          'got %s'%parse_E(r))
    # Strictness retained: genuine size sentinel still requires a ZIP64 locator.
    q=mk_classic_exact(2,patch_bytes=0xFFFFFFFF)
    r=run('defect2_neg_size_sentinel',q)
    check('defect2_neg_size_sentinel/rejected',
          parse_E(r)==[('seek_incomplete','missing_zip64_locator')] and len(r['D'])==0,
          'got %s'%parse_E(r))
    # Genuine ZIP64 archive with locator still accepted, identical to reference.
    z=B/'fixtures/zip64-70003-late.jar'
    a=run('zip64_genuine/seek',z); b=run('zip64_genuine/reference',z,engine='zip-reference')
    check('zip64_genuine/complete_metadata',len(a['E'])==0 and a['D']==b['D'] and len(a['D'])==1,
          'seek E=%s D=%d ref D=%d'%(a['E'][:1],len(a['D']),len(b['D'])))

# ---------- existing differential / resource-boundary / cleanup suite ----------
def test_differential():
    for name in ['comment-boundary-True-False.jar','selected-5000.jar',
                 'selected-empty-12000.jar','selected-longnames.jar']:
        f=B/'fixtures'/name
        a=run('diff/%s/seek'%name,f); b=run('diff/%s/reference'%name,f,engine='zip-reference')
        check('diff/%s'%name,len(a['E'])==0 and a['D']==b['D'] and len(a['D'])>0,
              'seek E=%s D=%d ref D=%d'%(a['E'][:1],len(a['D']),len(b['D'])))
    p=F/'module-many-provides.jar'
    with zipfile.ZipFile(p,'w') as z: z.writestr('module-info.class',module(30000))
    a=run('diff/module-many-provides/seek',p); b=run('diff/module-many-provides/reference',p,engine='zip-reference')
    # Module-provides descriptor emission order differs between engines; complete
    # metadata is compared order-insensitively (same established semantics).
    check('diff/module-many-provides',len(a['E'])==0 and sorted(a['D'])==sorted(b['D']) and len(a['D'])==30000,
          'seek E=%s D=%d ref D=%d'%(a['E'][:1],len(a['D']),len(b['D'])))
    for char in ['é','€','𐀀']:
        for split in range(1,len(char.encode())):
            p=F/('utf8-%x-%d.jar'%(ord(char),split))
            with zipfile.ZipFile(p,'w') as z:
                zi=zipfile.ZipInfo(S); zi.comment=b'x'*(8192-split)+char.encode(); z.writestr(zi,V)
            a=run('diff/utf8-%x-%d/seek'%(ord(char),split),p)
            b=run('diff/utf8-%x-%d/reference'%(ord(char),split),p,engine='zip-reference')
            check('diff/utf8-%x-%d'%(ord(char),split),
                  len(a['E'])==0 and a['D']==b['D'] and len(a['D'])==1,
                  'seek E=%s D=%d ref D=%d'%(a['E'][:1],len(a['D']),len(b['D'])))

def test_malformed_corpus():
    for name in ['comment-invalid-False-False.jar','ignored-overlap.jar','late-crc.jar']:
        f=B/'fixtures'/name
        r=run('malformed/%s'%name,f)
        check('malformed/%s/rejected'%name,len(r['E'])>=1 and len(r['D'])==0,
              'E=%s D=%d'%(parse_E(r)[:1],len(r['D'])))
    check('malformed/late-crc.jar/detail',
          any(d==('seek_incomplete','selected_size_or_crc_mismatch') for d in parse_E(rows[-1])),
          'got %s'%parse_E(rows[-1]))
    for f in sorted((B/'fixtures').glob('extra-zip64-size-*.jar')):
        r=run('zip64extra/%s'%f.name,f)
        size=int(f.name[len('extra-zip64-size-'):-len('.jar')])
        if size in (0,8,16,24,28):
            # Spec-valid ZIP64 extra block lengths must be accepted, with complete
            # metadata identical to the JDK reference (pinned, not observed).
            check('zip64extra/%s/accepted'%f.name,
                  len(r['E'])==0 and len(r['D'])==1,'E=%s D=%d'%(parse_E(r)[:1],len(r['D'])))
            b=run('zip64extra/%s/reference'%f.name,f,engine='zip-reference')
            check('zip64extra/%s/differential'%f.name,b['code']==0 and r['D']==b['D'],
                  'seek D=%d ref D=%d'%(len(r['D']),len(b['D'])))
        else:
            check('zip64extra/%s/rejected'%f.name,
                  parse_E(r)==[('seek_incomplete','invalid_zip64_extra_block_length')] and len(r['D'])==0,
                  'got %s D=%d'%(parse_E(r),len(r['D'])))

def a_eq(r,b): return r['D']==b['D']

def test_fault_injection():
    f=B/'fixtures/quota-atomic.jar'
    r=run('fault/quota_atomic_replay_failure',f,
          props=['-Dspike.failSpillReadAfter=800','-Dspike.certainty=true'])
    check('fault/quota_atomic_replay_failure/diagnostic',
          parse_E(r)==[('seek_result_incomplete','java.io.IOException: injected_spill_read_failure')],
          'got %s'%parse_E(r))
    check('fault/quota_atomic_replay_failure/no_partial_results',len(r['D'])==0,
          'transactional discard violated: D=%s'%r['D'][:1])
    check('fault/quota_atomic_replay_failure/certainty_line',r['R']==['R certainty=POSSIBLE'],
          'expected certainty=POSSIBLE with diagnostics present, got %s'%r['R'])
    safe=mk_valid(); f=B/'fixtures/late-crc.jar'
    r=run('fault/late_crc_chain',f,others=[safe],props=['-Dspike.certainty=true'])
    check('fault/late_crc_chain/diagnostic',
          parse_E(r)==[('seek_incomplete','selected_size_or_crc_mismatch')],'got %s'%parse_E(r))
    check('fault/late_crc_chain/prior_evidence_only',
          len(r['D'])==1 and 'valid-first.jar' in r['D'][0],'D=%s'%r['D'][:1])
    check('fault/late_crc_chain/certainty_line',r['R']==['R certainty=POSSIBLE'],
          'expected certainty=POSSIBLE with diagnostics present, got %s'%r['R'])

if __name__=='__main__':
    which=sys.argv[1] if len(sys.argv)>1 else 'all'
    test_defect1(); test_defect2()
    test_differential(); test_malformed_corpus(); test_fault_injection()
    (W/('results-%s.json'%which)).write_text(json.dumps(rows,indent=2))
    print('\n==== %d checks failed ===='%len(failures),flush=True)
    for t,d in failures: print('FAIL %s %s'%(t,d),flush=True)
    sys.exit(1 if failures else 0)
