import {notFound} from 'next/navigation';
import Harness from './Harness';
export const dynamic='force-dynamic';
export default function Page(){if(process.env.HARBOR_RECOVERY_LAB!=='1')notFound();return <Harness/>;}
