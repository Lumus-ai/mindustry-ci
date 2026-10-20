# render results/snap.log (+ pvp_samples.log player->team) into results/pvp.mp4 (schematic top-down replay, not game graphics)
import re,subprocess,sys,os
from PIL import Image,ImageDraw
L=open('results/snap.log').read().splitlines()
mp=[l for l in L if l.startswith('MAP ')]
snaps=[l for l in L if l.startswith('SNAP ')]
if not mp or not snaps: print('no data'); sys.exit(0)
_,W,H,tiles=mp[0].split(' ',3); W=int(W);H=int(H)
S=max(2,min(6,900//max(W,H)))
pl={}
for l in open('results/pvp_samples.log'):
    if 'PVP ' in l:
        for m in re.finditer(r'(\w+?)=(\w+),',l.split('teams=')[0]): pl[m.group(2)]=m.group(1)
pal=['#ffd34d','#ff5050','#4da6ff','#5cff7a','#d36bff','#ff9a3c']
bg=Image.new('RGB',(W*S,H*S),'#15181d'); d=ImageDraw.Draw(bg)
for y in range(H):
    for x in range(W):
        c=tiles[y*W+x]
        if c!='.': d.rectangle([x*S,(H-1-y)*S,x*S+S-1,(H-1-y)*S+S-1],fill='#3b4048' if c=='#' else '#1f3b5c')
os.makedirs('results/frames',exist_ok=True); n=0
for sl in snaps:
    try: _,t,teams,B,U=(sl.split(' ',1)[0],)+tuple(sl[5:].split('|'))
    except Exception: continue
    tn={};col={}
    for i,p in enumerate(teams.split(',')):
        if '=' in p: k,v=p.split('=');tn[k]=v;col[k]=pal[i%len(pal)]
    im=bg.copy();d=ImageDraw.Draw(im)
    for b in B.split(';'):
        if not b: continue
        k,x,y,s,c=b.split(',');x,y,s=int(x),int(y),int(s)
        px=(x-(s-1)//2)*S;py=(H-1-y-(s-1)//2)*S
        d.rectangle([px,py-(s-1)*S,px+s*S-1,py+S-1],fill=col.get(k,'#888'),outline='#fff' if c=='1' else None)
    for u in U.split(';'):
        if not u: continue
        k,x,y,f=u.split(',');x,y=int(x)*S,(H-1-int(y))*S
        d.ellipse([x-2,y-2,x+2,y+2],fill='#fff' if f=='0' else '#000',outline=col.get(k,'#888'))
    ly=4
    for k,v in tn.items():
        d.text((4,ly),f"{v}: {pl.get(v,'?')}",fill=col.get(k,'#fff'));ly+=12
    d.text((4,ly),f"t={t}",fill='#fff')
    im.save(f'results/frames/f{n:05d}.png');n+=1
subprocess.run(['ffmpeg','-y','-loglevel','error','-framerate','6','-i','results/frames/f%05d.png','-pix_fmt','yuv420p','-vf','pad=ceil(iw/2)*2:ceil(ih/2)*2','results/pvp.mp4'])
print('frames',n)
