"""Validate same-build ABBA profiling data; native durations are inclusive, never summed."""
import json,math,sys
from pathlib import Path

def quantiles(values):
    values=sorted(values)
    assert values and all(v>=0 for v in values)
    return {'n':len(values),'p50':values[math.ceil(len(values)*.5)-1], 'p95':values[math.ceil(len(values)*.95)-1]}

def summarize(path):
    data=json.loads(path.read_text());assert data['schema']==1 and data['passed']
    names=data['metrics'];assert len(names)==16 and len(set(names))==16
    rows=data['samples'];assert len(rows)==160
    result={'scope':data['scope'],'duration_unit':'ms','native_values_inclusive_not_additive':True,'trials':{}}
    for scenario in ['none','hide64']:
        subset=[r for r in rows if r['scenario']==scenario];assert len(subset)==80
        assert len({r['ruleFileHash'] for r in subset})==1
        for block,condition in enumerate(['bypass','enabled','enabled','bypass']):
            samples=[r for r in subset if r['block']==block]
            assert len(samples)==20 and {r['iteration'] for r in samples}==set(range(20))
            assert all(r['condition']==condition and r['candidateCount']==16 for r in samples)
            expected=data['filteredPrefixHash'] if scenario=='hide64' and condition=='enabled' else data['originalPrefixHash']
            assert all(r['candidateHash']==expected for r in samples)
            trial={field:quantiles([r[field]/1e6 for r in samples]) for field in ['initialWallNs','explicitRangeWallNs']}
            for phase in ['initialNative','rangeNative']:
                assert all(len(r[phase])==33 and r[phase][0]==1 for r in samples)
                assert all(v>=0 for r in samples for v in r[phase][1:])
                trial[phase]={name:{'duration_ms':quantiles([r[phase][1+2*i]/1e6 for r in samples]),
                    'count':quantiles([r[phase][2+2*i] for r in samples])} for i,name in enumerate(names)}
            result['trials'][f'{scenario}-{block}-{condition}']=trial
    return result

if __name__=='__main__':
    path=Path(sys.argv[1]);result=summarize(path)
    target=path.with_name('candidate-attribution-summary.json');target.write_text(json.dumps(result,indent=2)+'\n')
    for name,trial in result['trials'].items():
        print(name,'initial wall ms',trial['initialWallNs'],'explicit range ms',trial['explicitRangeWallNs'])
    print('Raw output hashes, ABBA counts, fixed rules and native schemas verified; native timings inclusive, do not sum.')
