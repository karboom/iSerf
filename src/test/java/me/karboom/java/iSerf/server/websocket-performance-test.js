import { io } from 'socket.io-client';

const AGENT_COUNT = 2000;
const SERVER_IP = '127.0.0.1';
const SERVER_PORT = 9098;

async function createClients() {
    console.log('===========================================');
    console.log(`开始创建 ${AGENT_COUNT} 个客户端连接...`);
    console.log('===========================================');

    const sockets = [];
    const agentIds = new Array(AGENT_COUNT);
    let connectedCount = 0;
    let createdCount = 0;

    const socketUrl = `http://${SERVER_IP}:${SERVER_PORT}/user`;

    const createStartTime = Date.now();

    // 创建所有连接
    for (let i = 0; i < AGENT_COUNT; i++) {
        const index = i;
        const socket = io(socketUrl, {
            transports: ['websocket'],
            reconnection: false,
            forceNew: true,
            timeout: 60000
        });

        socket.on('connect', () => {
            connectedCount++;

            // 可选：创建 agent
            socket.emit('agent/create', JSON.stringify({ prompt: `测试 Agent ${index}` }), (response) => {
                if (response) {
                    const res = JSON.parse(response);
                    agentIds[index] = res.agentId;
                    createdCount++;
                }
            });
        });

        socket.on('connect_error', (error) => {
            console.log(`连接错误 ${index}: ${error.message}`);
        });

        socket.connect();
        sockets.push(socket);
    }

    // 等待所有连接完成
    await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
            reject(new Error('连接超时'));
        }, 120000);

        const checkInterval = setInterval(() => {
            if (connectedCount >= AGENT_COUNT) {
                clearTimeout(timeout);
                clearInterval(checkInterval);
                resolve();
            }
        }, 100);
    });

    const connectTime = Date.now() - createStartTime;
    console.log(`连接完成，耗时：${connectTime}ms, 连接数：${connectedCount}`);

    // 等待 agent 创建（如果启用了 agent 创建）
    await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
            reject(new Error('创建 agent 超时'));
        }, 120000);

        const checkInterval = setInterval(() => {
            if (createdCount >= AGENT_COUNT) {
                clearTimeout(timeout);
                clearInterval(checkInterval);
                resolve();
            }
        }, 100);
    });

    const totalTime = Date.now() - createStartTime;
    console.log('===========================================');
    console.log('客户端测试结果:');
    console.log(`  客户端总数：${AGENT_COUNT}`);
    console.log(`  连接成功数：${connectedCount}`);
    console.log(`  创建成功数：${createdCount}`);
    console.log(`  总耗时：${totalTime}ms`);
    console.log(`  平均每个客户端耗时：${(totalTime / AGENT_COUNT).toFixed(2)}ms`);
    console.log('===========================================');

    // 验证
    if (connectedCount !== AGENT_COUNT) {
        throw new Error(`期望连接数 ${AGENT_COUNT}, 实际 ${connectedCount}`);
    }

    // 保持连接运行 1 小时
    console.log('保持连接运行 1 小时...');
    await new Promise(resolve => setTimeout(resolve, 3600 * 1000));

    // 关闭所有连接
    console.log('开始关闭客户端连接...');
    sockets.forEach(socket => {
        if (socket.connected) {
            socket.disconnect();
        }
    });
    console.log('客户端连接已关闭');
}

// 运行测试
createClients()
    .then(() => {
        console.log('测试完成');
        process.exit(0);
    })
    .catch((error) => {
        console.error('测试失败:', error);
        process.exit(1);
    });