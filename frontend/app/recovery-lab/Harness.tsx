'use client';
import {prepareOpfsDownload,startOpfsDownload} from '../../lib/opfs-download';
import {useEffect} from 'react';
import {upload,fingerprint} from '../../lib/transfer';
import {download,checkpoint,forgetCheckpoint} from '../../lib/download';
const transfers={prepareOpfsDownload,startOpfsDownload,upload,fingerprint,download,checkpoint,forgetCheckpoint};
export default function Harness(){useEffect(()=>{(window as any).__harborRecovery=transfers;},[]);return <p>Local recovery test harness</p>}
