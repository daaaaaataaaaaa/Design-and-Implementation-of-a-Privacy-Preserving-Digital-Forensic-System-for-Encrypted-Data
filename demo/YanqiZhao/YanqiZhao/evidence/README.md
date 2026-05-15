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
