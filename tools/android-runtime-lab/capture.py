#!/usr/bin/env python3
"""Capture one explicitly new Compose build on a credential-free lab emulator.

Never resume a run: output directories and runtimeTaskIds must be fresh. Generated
artifacts belong outside the repository. This does not start a desktop build.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

CONFIG = '''buffers { size_kb: 8192 fill_policy: RING_BUFFER }
duration_ms: 1200000
write_into_file: true
file_write_period_ms: 1000
flush_period_ms: 1000
max_file_size_bytes: 268435456
data_sources { config { name: "linux.process_stats" process_stats_config {
  scan_all_processes_on_start: true proc_stats_poll_ms: 1000
} } }
data_sources { config { name: "linux.sys_stats" sys_stats_config {
  meminfo_period_ms: 1000 vmstat_period_ms: 1000
} } }
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_process_exit"
  ftrace_events: "task/task_newtask" ftrace_events: "oom/oom_score_adj_update"
  ftrace_events: "lowmemorykiller/lowmemory_kill"
} } }
'''

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--task-id', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'compose-[a-z0-9-]{1,64}', args.task_id):
        raise ValueError('Expected an explicit fresh compose task ID')
    if args.output.resolve().is_relative_to(Path(__file__).resolve().parents[2]):
        raise ValueError('Capture output must be outside the repository/iCloud workspace')
    # Refuse to overwrite or infer that an interrupted previous run is resumable.
    args.output.mkdir(parents=True, exist_ok=False)
    adb = [args.adb, '-s', args.serial]
    def call(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=20).strip()
    package = 'dev.srimi.antigravityruntime.lab'
    uid_line = call('shell','pm','list','packages','-U', package)
    uid = next(line.split('uid:')[1] for line in uid_line.splitlines()
               if line.startswith('package:' + package + ' uid:'))
    processes = []
    streams = []
    metadata = {'taskId':args.task_id,'serial':args.serial,'uid':uid,'startedAt':time.time()}
    (args.output/'trace-config.pbtxt').write_text(CONFIG)
    def start(command, output, error):
        out = (args.output/output).open('wb'); err = (args.output/error).open('wb')
        streams.extend([out,err])
        process = subprocess.Popen(adb+command,stdin=subprocess.PIPE,stdout=out,stderr=err)
        processes.append(process); return process
    try:
        trace_path='/data/misc/perfetto-traces/'+args.task_id+'.pftrace'
        launched=subprocess.run(adb+['shell','perfetto','--background','--txt','-c','-','-o',trace_path],
            input=CONFIG,text=True,capture_output=True,timeout=20,check=True)
        (args.output/'perfetto-start.txt').write_text(launched.stdout+launched.stderr)
        trace_pid=int(launched.stdout.strip().splitlines()[-1])
        trace_stat=call('shell','cat',f'/proc/{trace_pid}/stat').split()[21]
        metadata.update({'deviceTracePid':trace_pid,'deviceTracePath':trace_path})
        logcat = start(['logcat','-T','1','-v','threadtime','-s','AntigravityRuntime:I','lmkd:V','Watchdog:V','ActivityManager:E','DEBUG:E'], 'logcat.txt','logcat-stderr.txt')
        time.sleep(2)
        if 'perfetto' not in call('shell','ps','-p',str(trace_pid),'-o','NAME'):
            raise RuntimeError('Trace not live before build; do not launch task')
        build = start(['shell','am','instrument','-w','-r','-e','runtimeTaskId',args.task_id,'-e','class',
            'dev.srimi.antigravityruntime.AndroidRuntimeTest#gradleBuildsComposeSampleOnAndroid',
            package+'.test/androidx.test.runner.AndroidJUnitRunner'], 'instrumentation.txt','instrumentation-stderr.txt')
        metadata.update({'hostBuildPid':build.pid})
        (args.output/'capture.json').write_text(json.dumps(metadata,indent=2)+'\n')
        print(json.dumps(metadata),flush=True)
        with (args.output/'memory-samples.jsonl').open('w') as samples:
            while True:
                sample = {'hostTime':time.time()}
                try:
                    sample['guestUptime']=call('shell','cat','/proc/uptime')
                    sample['meminfo']={line.split(':')[0]:int(line.split()[1]) for line in
                        call('shell','cat','/proc/meminfo').splitlines() if line.split(':')[0] in
                        {'MemTotal','MemAvailable','MemFree','Cached','AnonPages','SwapTotal','SwapFree'}}
                    ps=call('shell','ps','-A','-n','-o','UID,PID,PPID,RSS,NAME')
                    sample['labProcesses']=[line.split() for line in ps.splitlines()[1:] if line.split()[0]==uid]
                except Exception as error: sample['observationError']=str(error)
                samples.write(json.dumps(sample)+'\n'); samples.flush()
                code=build.poll()
                if code is not None: metadata['adbExit']=code; break
                if time.time()-metadata['startedAt']>1260:
                    raise TimeoutError('Build observation deadline: preserve state; never automatically restart')
                time.sleep(5)
        metadata['finishedAt']=time.time()
    except Exception as error:
        metadata['captureError']=str(error)
        raise
    finally:
        for process in reversed(processes):
            if process.poll() is None: process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
        if 'deviceTracePid' in metadata:
            try:
                if call('shell','cat',f'/proc/{trace_pid}/stat').split()[21]==trace_stat:
                    call('shell','kill','-TERM',str(trace_pid))
            except subprocess.CalledProcessError: pass  # Already ended.
            previous=-1; stable=0
            for _ in range(10):
                size=int(call('shell','stat','-c','%s',trace_path))
                stable=stable+1 if size==previous and size>0 else 0
                if stable>=2: break
                previous=size; time.sleep(1)
            subprocess.run(adb+['pull',trace_path,str(args.output/'build.pftrace')],check=True,
                stdout=subprocess.DEVNULL)
        for stream in streams: stream.close()
        metadata['traceBytes']=(args.output/'build.pftrace').stat().st_size if (args.output/'build.pftrace').exists() else 0
        (args.output/'capture.json').write_text(json.dumps(metadata,indent=2)+'\n')
        print(json.dumps(metadata),flush=True)

if __name__=='__main__': main()
