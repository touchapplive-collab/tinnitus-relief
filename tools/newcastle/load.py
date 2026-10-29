# Loader for the Newcastle dataset (doi:10.25405/data.ncl.27109693). The data file is NOT in this repo;
# download Final_Data_Set.xlsx yourself and set F below. Used for docs/newcastle-analysis.md.
import pandas as pd, re, numpy as np
F='/tmp/claude-0/-home-claude/e1a94fa0-cd09-5402-b005-c2f50267f688/scratchpad/ds/d/Final_Data_Set.xlsx'
X=pd.read_excel(F,sheet_name=None,header=None)
def norm(s): return re.sub(r'[^a-z0-9]','',str(s).lower())
def num(v):
    try: return float(v)
    except: return np.nan
def questionnaire():
    q=X['Questionnaire results'].iloc[1:].reset_index(drop=True)
    d=pd.DataFrame({'row':q.index+2,'ts':q[0],'cond':q[1],'id':q[2],'gen':q[3],'nrs':q[5].map(num),'tia':q[12].map(num),'thi':q[39].map(num),'tfi':q[82].map(num)})
    d['block']=d.id.notna().cumsum()
    d['pid']=d.groupby('block').id.transform('first')
    return d
def tracking():
    t=X['Tracking ppts info'].iloc[1:]
    t=t[t[4].astype(str).str.match(r'^\d')]
    return pd.DataFrame({'pid':t[0],'key':t[0].map(norm),'age':t[1],'sex':t[2],'match':t[3],'tcond':t[4].astype(str).str.extract(r'\((\w+)\)')[0].str.lower(),'file1':t[8],'file2':t[14]})
def intro():
    i=X['Intro q results']
    rows={1:'pid',5:'hear_diff',7:'aid',12:'duration',13:'ear',14:'type',15:'loud',16:'distress',17:'loud_sound_effect',18:'after_loud_effect',20:'hl_profile',21:'like',22:'match',23:'matched_ok'}
    d=pd.DataFrame({v:i.iloc[k,1:].values for k,v in rows.items()})
    d=d[d.pid.notna()]; d['key']=d.pid.map(norm); return d
def tokens(s): return [norm(p) for p in re.split(r'[/ ]+', str(s).split(' - ')[0]) if norm(p)]
MANUAL={'ro0802':'ro0208'}  # questionnaire ID with transposed digits (same person: only RO entry, noise SA)
def linked():
    d=questionnaire(); t=tracking(); it=intro()
    tmap={}
    for _,r in t.iterrows():
        for k in tokens(r.pid): tmap.setdefault(k,r)
    imap={}
    for _,r in it.iterrows():
        for k in tokens(r.pid): imap.setdefault(k,r)
    P=[]
    for blk,g in d.groupby('block'):
        ks=[MANUAL.get(k,k) for k in tokens(g.pid.iloc[0])]
        tr=next((tmap[k] for k in ks if k in tmap),None); ir=next((imap[k] for k in ks if k in imap),None)
        rec={'pid':g.pid.iloc[0],'gen':int(g.gen.iloc[0]),'order':'AS' if g.cond.iloc[0]=='A_Start' else 'SA'}
        for _,r in g.iterrows(): rec[r.cond]=r.nrs; rec['thi_'+r.cond]=r.thi; rec['tfi_'+r.cond]=r.tfi
        rec['tcond']=None if tr is None else tr.tcond
        rec['tmatch']=None if tr is None else tr.match
        rec['age']=None if tr is None else tr.age
        for c in ['duration','hl_profile','type','like','match','loud_sound_effect','after_loud_effect','hear_diff','aid','loud']:
            rec['i_'+c]=None if ir is None else ir[c]
        rec['intro_found']=ir is not None
        P.append(rec)
    P=pd.DataFrame(P); P['mod']=P.gen.map({1:'amp',2:'noise',3:'phase'}); return P,t
