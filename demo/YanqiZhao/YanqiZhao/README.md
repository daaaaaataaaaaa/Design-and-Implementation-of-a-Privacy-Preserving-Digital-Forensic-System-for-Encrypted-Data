更新如何运行
步骤1：启动Ganache
1. 打开Ganache软件
2. 点击 "QuickStart" 
3. 确认 RPC Server 地址是: HTTP://127.0.0.1:7545
4. **保持Ganache窗口打开，不要关闭**

步骤2：配置MetaMask
安装MetaMask
Chrome: 搜索 "MetaMask" 添加扩展
创建钱包（本地测试，密码随便设，用了ganache不会有费用）
添加Ganache网络
点击MetaMask → 网络切换下拉 → "添加网络"

填入：
网络名称: Ganache
RPC URL: http://127.0.0.1:7545
链ID: 1337
货币符号: ETH
保存

导入Ganache账户
在Ganache界面，点击第一个账户右侧的钥匙图标，复制私钥
MetaMask → 点击头像 → "导入账户"
粘贴私钥 → 导入


**方案A（这个最简单最迅速 但是得把合约部署在remix网站上）**
打开Remix (remix.ethereum.org)
把 EvidenceRegistry.sol 粘贴进去
编译（Ctrl+S）

部署：
Environment 选择 "Web3 Provider"
输入 http://127.0.0.1:7545
点击 "Deploy"
**复制部署后的合约地址**

**方式B：用Hardhat部署（需要安装依赖 这个我没试过不知道能不能成功）**
控制台输入
cd integrated-forensic-platform
npm init -y
npm install --save-dev hardhat @nomiclabs/hardhat-waffle ethers
npx hardhat
选择 "Create a basic sample project"
把 EvidenceRegistry.sol 复制到 contracts/ 目录
修改部署脚本，然后：
npx hardhat run scripts/deploy.js --network ganache


**把地址粘贴在在BlockchainEvidence.tsx 中找到这一行（大约第6行）：**
const [contractAddress, setContractAddress] = useState("0x合约地址");


步骤5：确保ABI正确
检查 src/lib/blockchain.ts 文件中的 evidenceRegistryAbi 是否与合约匹配。

如果ABI不匹配，从Remix重新复制：
Remix编译面板 → 点击 "ABI" 按钮 → 复制全部
替换 blockchain.ts 中的 evidenceRegistryAbi

最后即可启动前端





以下是旧版 不用看----------------------------------------------------------------------------------------------
操作过程：下载Ganache

点开Ganache，启动quick start，记住上栏中RPC sever的端口（一般后四位为7545）

首先登入Remix网站https://remix.ethereum.org
在contracts目录下新建EvidenceRegistry.sol，替换对应代码
点开侧边栏solidity compiler→Advanced Configurations中EVM Version改为Paris
再把侧边栏deploy&run transactions中Environment的Remix VM改为Dev，旁边改为Ganache provider

点击下面蓝色Deploy进行部署，此刻在弹出界面检查端口（一般为7545）
成功部署之后会显示合约地址，复制这个合约地址到html网站代码对应位置中（代码中第158行）

以管理员权限打开cmd窗口，cd进入到html文件的对应文件夹目录里
输入指令npx http-server -p 8080      启动http-server
成功挂载之后在网页输入http://localhost:8080 

网页上首先点击链接Ganache，即可测试模拟区块链
