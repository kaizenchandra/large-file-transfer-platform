import type {NextConfig} from 'next';
const config: NextConfig = {async rewrites(){return [{source:'/api/:path*',destination:`${process.env.API_ORIGIN || 'http://localhost:18080'}/api/:path*`}];}};
export default config;
