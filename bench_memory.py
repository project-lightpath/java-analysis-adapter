"""Memory-vs-entry-count benchmark for the uncapped seekable enumeration.
Accuracy is the pass condition at every scale: exit 0, zero diagnostics,
exactly one correct descriptor, cenEntries == N. Time is recorded but not judged.
Each point: freshly generated archive (N-1 ignored stored entries + 1 known
META-INF/services file), single child JVM -Xms16m -Xmx32m, /usr/bin/time -l RSS,
scoped temp root cleaned and checked. Raw/directory I/O budgets are raised for
large N so the measurement is memory, not the lab I/O policy; spill disk and
heap are left at production-relevant bounds (128 MiB spill quota, 32 MiB heap).
"""
import pathlib, subprocess, zipfile, json, time, tempfile, os, re, sys, platform
W=pathlib.Path(__file__).resolve().parent
J=os.environ.get('BENCH_JAVA','/Users/jgil/.cache/project-lightpath/jdk/jdk-21.0.13+9/Contents/Home/bin/')
BASE=os.environ.get('BENCH_BASE') or (
    str(W.parent/'flpath-5078-fix'/'target/classes')+':'
    +(W.parent/'flpath-5078-fix'/'target/safety-isolated-classpath.txt').read_text().strip())
DARWIN=platform.system()=='Darwin'
CP=str(W/'classes')+':'+BASE
F=W/'bench-fixtures'; F.mkdir(exist_ok=True)
S='META-INF/services/demo.Provider'; V=b'demo.DefaultProvider\n'

def build(n):
    p=F/('entries-%d.jar'%n)
    if p.exists(): return p
    with zipfile.ZipFile(p,'w',zipfile.ZIP_STORED,allowZip64=True) as z:
        z.writestr(S,V)
        for i in range(n-1): z.writestr('pkg/e%09d.class'%i,b'')
    return p

def run(n,p):
    # ~367 raw bytes/entry measured on the baseline: raise I/O budgets so N up to
    # 1M completes; heap (32 MiB) stays fixed. Spill quota (disk, ~136 B/entry)
    # is raised past ~980k entries for the same reason.
    props=['-Dspike.rawMax=6442450944','-Dspike.directoryMax=6442450944'] if n>100000 else []
    if n>800000: props+=['-Dspike.spillMax=1073741824']
    owned=pathlib.Path(tempfile.mkdtemp(prefix='bench-',dir=W))
    tflag=['-l'] if DARWIN else ['-v']
    cmd=['/usr/bin/time',*tflag,J+'java','-Xms16m','-Xmx32m','-Dspike.tempRoot='+str(owned),
         '-Dspike.root='+str(W),*props,'-cp',CP,
         'com.projectlightpath.analysis.java.SeekProbe','seek','16777216',str(p)]
    t=time.monotonic()
    r=subprocess.run(cmd,capture_output=True,text=True,timeout=1800)
    wall=time.monotonic()-t
    residual=list(owned.rglob('*'))
    for q in sorted(residual,key=lambda x:len(x.parts),reverse=True):
        q.rmdir() if q.is_dir() else q.unlink()
    owned.rmdir()
    out=r.stdout.splitlines()
    D=[x for x in out if x.startswith('D\t')]; E=[x for x in out if x.startswith('E\t')]
    Sline=[x for x in out if x.startswith('S ')]; Tline=[x for x in out if x.startswith('T ')]
    sm=dict(kv.split('=',1) for kv in Sline[0].split()[1:]) if Sline else {}
    tm=dict(kv.split('=',1) for kv in Tline[0].split()[1:]) if Tline else {}
    rss_gnu=re.search(r'[Mm]aximum resident set size \(kbytes\):\s*(\d+)',r.stderr)
    rss_bsd=re.search(r'(\d+)\s+[Mm]aximum resident set size',r.stderr)
    # GNU time -v reports kbytes (value after label); BSD time -l reports bytes.
    rss_bytes=int(rss_gnu.group(1))*1024 if rss_gnu else (int(rss_bsd.group(1)) if rss_bsd else None)
    ok=(r.returncode==0 and len(D)==1 and 'serviceType=demo.Provider' in D[0]
        and 'demo.DefaultProvider' in D[0] and not E and sm.get('cenEntries')==str(n)
        and not residual)
    return dict(n=n,accurate=ok,code=r.returncode,descriptors=len(D),diagnostics=len(E),
        cenEntries=sm.get('cenEntries'),heapPoolPeakSum=int(sm.get('heapPoolPeakSum',0)),
        heapMax=int(sm.get('heapMax',0)),maxRssBytes=rss_bytes,
        spillPeak=int(tm.get('spillPeak',0)),spillWritten=int(tm.get('spillWritten',0)),
        raw=sm.get('raw'),scanMs=sm.get('ms'),wallSeconds=round(wall,1),
        props=props,residual=len(residual))

if __name__=='__main__':
    sizes=[1000,5000,10000,25000,50000,65535,100000,200000,400000,800000,1000000]
    rows=[]
    for n in sizes:
        p=build(n); row=run(n,p); rows.append(row)
        print('%8d accurate=%s heapPeak=%7.2fMB rss=%7.1fMB spillPeak=%7.2fMB scan=%ss wall=%.1fs raw=%sMB'%(
            n,row['accurate'],row['heapPoolPeakSum']/1e6,(row['maxRssBytes'] or 0)/1e6,
            row['spillPeak']/1e6,round(float(row['scanMs'] or 0)/1000,1),row['wallSeconds'],
            round(int(row['raw'] or 0)/1e6,1)),flush=True)
    (W/'bench-memory-vs-entries.json').write_text(json.dumps(rows,indent=1))
    bad=[r for r in rows if not r['accurate']]
    print('==== %d/%d accurate ===='%(len(rows)-len(bad),len(rows)))
    sys.exit(1 if bad else 0)
