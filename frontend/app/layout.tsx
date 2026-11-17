import './style.css';
import {WorkspaceProvider} from '../components/Workspace';
export const metadata={title:'Harbor • Secure file transfers'};
export default function Layout({children}:{children:React.ReactNode}){return <html lang="en"><body><WorkspaceProvider>{children}</WorkspaceProvider></body></html>}
