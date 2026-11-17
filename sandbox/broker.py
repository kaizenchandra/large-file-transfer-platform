#!/usr/bin/env python3
"""Trusted local Docker broker. Parser containers never receive the Docker socket or network."""
import http.server, os, pathlib, secrets, subprocess, tempfile, threading, math
TOKEN=os.environ['SANDBOX_TOKEN']
IMAGE=os.environ.get('SANDBOX_IMAGE','harbor-media:local')
SLOTS=threading.BoundedSemaphore(2)
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def do_POST(self):
  if not secrets.compare_digest(self.headers.get('Authorization',''),'Bearer '+TOKEN): self.send_error(401);return
  kind=self.path.removeprefix('/')
  if kind not in ('pdf','video','transcode'):self.send_error(400);return
  try:length=int(self.headers.get('Content-Length','0'))
  except ValueError:self.send_error(400);return
  if not 0<length<=64*1024*1024:self.send_error(413);return
  if not SLOTS.acquire(blocking=False):self.send_error(429);return
  name='harbor-parser-'+secrets.token_hex(12)
  try:
   with tempfile.TemporaryDirectory(prefix='harbor-parser-') as directory:
    root=pathlib.Path(directory);root.chmod(0o755);source=root/'input';out=root/'out';out.mkdir(mode=0o777);out.chmod(0o777)
    self.connection.settimeout(20)
    with source.open('wb') as f:
     left=length
     while left:
      block=self.rfile.read(min(left,131072))
      if not block:raise ValueError('Truncated input')
      f.write(block);left-=len(block)
    source.chmod(0o644)
    command=['docker','run','-d','--name',name,'--network','none','--cpus','1','--memory','512m','--memory-swap','512m','--pids-limit','64','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--ulimit','fsize=8388608:8388608','--tmpfs','/tmp:rw,noexec,nosuid,size=64m','--user','65534:65534','--mount',f'type=bind,src={source},dst=/input,readonly','--tmpfs','/out:rw,noexec,nosuid,size=16m,uid=65534,gid=65534',IMAGE]
    subprocess.run(command+['sleep','150'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=10,check=True)
    def execute(args,timeout=45):
     result=subprocess.run(['docker','exec',name]+args,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=timeout)
     if result.returncode:raise ValueError('Parser rejected input')
    def extract(file,limit):
     data=subprocess.run(['docker','exec',name,'head','-c',str(limit+1),file],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=10,check=True).stdout
     if len(data)>limit:raise ValueError('Output limit exceeded')
     return data
    def duration(file,output):
     # The shell text and all paths are fixed; no filename or content is interpolated.
     execute(['sh','-c','ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 '+file+' > '+output],10)
     value=float(extract(output,128).decode().strip())
     if not math.isfinite(value) or value<=0 or value>120:raise ValueError('Video duration policy')
     return value
    if kind=='transcode':
     original_duration=duration('/input','/out/source-duration')
     execute(['ffmpeg','-nostdin','-v','error','-protocol_whitelist','file,pipe','-i','/input','-map','0:v:0','-map','0:a?','-vf','scale=640:640:force_original_aspect_ratio=decrease:force_divisible_by=2','-c:v','libx264','-preset','veryfast','-crf','28','-threads','1','-c:a','aac','-b:a','96k','-movflags','+faststart','/out/transcode.mp4'])
     actual=duration('/out/transcode.mp4','/out/result-duration')
     if abs(actual-original_duration)>max(.25,original_duration*.02):raise ValueError('Output duration mismatch')
     execute(['ffmpeg','-nostdin','-v','error','-i','/out/transcode.mp4','-f','null','-'],15)
     data=extract('/out/transcode.mp4',8*1024*1024);mime='video/mp4'
     if len(data)<12 or data[4:8]!=b'ftyp':raise ValueError('Invalid MP4 output')
    else:
     parser=['pdftoppm','-f','1','-singlefile','-scale-to','1024','-png','/input','/out/preview'] if kind=='pdf' else ['ffmpeg','-nostdin','-v','error','-protocol_whitelist','file,pipe','-i','/input','-frames:v','1','-vf','scale=640:640:force_original_aspect_ratio=decrease:force_divisible_by=2','-threads','1','/out/preview.png']
     execute(parser);data=extract('/out/preview.png',8*1024*1024);mime='image/png'
     if not data.startswith(b'\x89PNG\r\n\x1a\n'):raise ValueError('Invalid output')
    self.send_response(200);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
  except (ValueError,subprocess.TimeoutExpired,subprocess.CalledProcessError,OSError):self.send_error(422,'Sandbox processing failed')
  finally:
   try:subprocess.run(['docker','rm','-f',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=10)
   finally:SLOTS.release()
if __name__=='__main__':http.server.ThreadingHTTPServer(('127.0.0.1',19091),Handler).serve_forever()
