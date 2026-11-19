import { useEffect, useState } from 'react';
import { errorText, type VelleClient } from '../api/VelleClient';
import { latestTicksDocument, tickDocument } from '../graphql/documents';

export interface ScheduleViewProps {
  client: VelleClient;
  schedule: string;
  /** Whether the application lets a caller fire a tick; without it the view only says when the schedule last ran. */
  ticksExposed: boolean;
}

interface LatestTick {
  schedule: string;
  at: string | null;
}

interface TickOutcome {
  at: string;
  rejections: { message: string }[];
}

interface TickVerdict {
  clean: boolean;
  text: string;
}

function latestTickText(latestTicks: LatestTick[], schedule: string): string {
  const latestTick = latestTicks.find(candidate => candidate.schedule === schedule);
  if (!latestTick) return `The system has no schedule named ${schedule}.`;
  return latestTick.at ? `Last ran at ${latestTick.at}.` : 'Has not run yet.';
}

function tickVerdict(outcome: TickOutcome): TickVerdict {
  if (outcome.rejections.length === 0) return { clean: true, text: `Ran at ${outcome.at}, nothing rejected.` };
  const messages = outcome.rejections.map(rejection => rejection.message).join('\n\n');
  return { clean: false, text: `Ran at ${outcome.at}\n${outcome.rejections.length} firing(s) rejected:\n\n${messages}` };
}

/** A schedule: when it last ran, and a button that ticks it when ticks are exposed. */
export function ScheduleView({ client, schedule, ticksExposed }: ScheduleViewProps) {
  const [latestText, setLatestText] = useState(' ');
  const [verdict, setVerdict] = useState<TickVerdict | null>(null);
  const [ticksRun, setTicksRun] = useState(0);

  useEffect(() => setVerdict(null), [schedule]);

  // read again after each tick run from here
  useEffect(() => {
    let abandoned = false;
    client.request<{ ticks: LatestTick[] }>(latestTicksDocument)
      .then(answer => { if (!abandoned) setLatestText(latestTickText(answer.ticks, schedule)); })
      .catch(thrown => { if (!abandoned) setLatestText(errorText(thrown)); });
    return () => { abandoned = true; };
  }, [client, schedule, ticksRun]);

  async function tick() {
    try {
      const answer = await client.request<{ tick: TickOutcome }>(tickDocument, { schedule });
      setVerdict(tickVerdict(answer.tick));
      setTicksRun(ticksRun + 1);
    } catch (thrown) {
      setVerdict({ clean: false, text: errorText(thrown) });
    }
  }

  return (
    <>
      <h2 className="title">{schedule}</h2>
      <p className="subtitle">{latestText}</p>
      {ticksExposed && (
        <>
          <p className="subtitle">Run every rule on this schedule now.</p>
          <button className="primary" onClick={tick}>Tick {schedule}</button>
        </>
      )}
      {verdict && <pre className={'outcome ' + (verdict.clean ? 'ok' : 'bad')}>{verdict.text}</pre>}
    </>
  );
}
