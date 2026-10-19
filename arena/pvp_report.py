import re
rows=[l for l in open('results/pvp_samples.log') if 'PVP ' in l]
first={};last={};dead={};pl={}
for l in rows:
    t=int(re.search(r'PVP (\d+)',l).group(1))
    for m in re.finditer(r'(\w+)=(\w+),',l.split('teams=')[0]): pl[m.group(1)]=m.group(2)
    for m in re.finditer(r'(\w+):core=(-?\d+):blds=(\d+):units=(\d+)',l):
        tm,hp,b,u=m.group(1),int(m.group(2)),int(m.group(3)),int(m.group(4)); first.setdefault(tm,t)
        last[tm]=(hp,b,u,t)
        if hp<=0 and tm not in dead: dead[tm]=t
over=[l for l in open('results/pvp_samples.log') if 'Game over' in l]
out="# PvP result\n"+(over[-1] if over else "no winner (timeout)\n")
out+="| team | bots | core hp end | buildings | units | core destroyed at |\n|---|---|---|---|---|---|\n"
for tm,(hp,b,u,t) in sorted(last.items(),key=lambda x:(x[0] in dead, -x[1][0])):
    bots=",".join(k for k,v in pl.items() if v==tm)
    out+=f"| {tm} | {bots} | {hp} | {b} | {u} | {dead.get(tm,'-')} |\n"
open('results/pvp_scoreboard.md','w').write(out);print(out)
