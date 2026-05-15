// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

contract EvidenceRegistry {
    
    struct EvidenceRecord {
        string caseId;
        string evidenceHash;
        string evidenceName;
        address submitter;
        uint256 timestamp;
        string description;
        bool isActive;
        string fileName;
        string attackType;
        string sourceIp;
        string targetUrl;
    }
    
    EvidenceRecord[] public records;
    mapping(bytes32 => bool) public hashExists;
    mapping(string => uint256[]) public caseRecords;
    mapping(bytes32 => address) public hashToSubmitter;
    mapping(bytes32 => uint256) public hashToRecordId;
    mapping(string => bool) public fileNameExists;
    mapping(string => uint256[]) public attackTypeRecords;
    mapping(string => uint256[]) public fileNameRecords;
    
    uint256 public totalRecords;
    
    event EvidenceStored(
        string indexed caseId,
        string evidenceHash,
        string evidenceName,
        address indexed submitter,
        uint256 timestamp
    );
    
    event EvidenceRevoked(
        string indexed caseId,
        string evidenceHash,
        address indexed revoker,
        uint256 timestamp
    );
    
    event TamperDetected(
        string evidenceHash,
        string expectedHash,
        address verifier,
        uint256 timestamp
    );
    
    function stringToBytes32(string memory source) public pure returns (bytes32 result) {
        bytes memory tempEmptyStringTest = bytes(source);
        if (tempEmptyStringTest.length == 0) {
            return 0x0;
        }
        assembly {
            result := mload(add(source, 32))
        }
    }
    
    function storeEvidence(
        string memory _caseId,
        string memory _evidenceHash,
        string memory _evidenceName,
        string memory _description
    ) public {
        bytes32 hashBytes = stringToBytes32(_evidenceHash);
        require(!hashExists[hashBytes], "Evidence hash already exists");
        require(bytes(_evidenceHash).length == 64, "Invalid hash format");
        
        EvidenceRecord memory newRecord = EvidenceRecord({
            caseId: _caseId,
            evidenceHash: _evidenceHash,
            evidenceName: _evidenceName,
            submitter: msg.sender,
            timestamp: block.timestamp,
            description: _description,
            isActive: true,
            fileName: "",
            attackType: "",
            sourceIp: "",
            targetUrl: ""
        });
        
        records.push(newRecord);
        uint256 recordId = records.length - 1;
        hashExists[hashBytes] = true;
        hashToSubmitter[hashBytes] = msg.sender;
        hashToRecordId[hashBytes] = recordId;
        caseRecords[_caseId].push(recordId);
        totalRecords++;
        
        emit EvidenceStored(_caseId, _evidenceHash, _evidenceName, msg.sender, block.timestamp);
    }
    
    function storeJSONEvidence(
        string memory _caseId,
        string memory _fileHash,
        string memory _evidenceName,
        string memory _description,
        string memory _fileName,
        string memory _attackType,
        string memory _sourceIp,
        string memory _targetUrl
    ) public returns (uint256) {
        require(!fileNameExists[_fileName], "File name already exists");
        
        storeEvidence(_caseId, _fileHash, _evidenceName, _description);
        
        bytes32 hashBytes = stringToBytes32(_fileHash);
        uint256 recordId = hashToRecordId[hashBytes];
        
        records[recordId].fileName = _fileName;
        records[recordId].attackType = _attackType;
        records[recordId].sourceIp = _sourceIp;
        records[recordId].targetUrl = _targetUrl;
        
        fileNameExists[_fileName] = true;
        fileNameRecords[_fileName].push(recordId);
        attackTypeRecords[_attackType].push(recordId);
        
        return recordId;
    }
    
    function verifyEvidence(string memory _evidenceHash) public view returns (bool) {
        bytes32 hashBytes = stringToBytes32(_evidenceHash);
        return hashExists[hashBytes];
    }
    
    function getRecord(uint256 _recordId) public view returns (EvidenceRecord memory) {
        require(_recordId < records.length, "Record ID does not exist");
        return records[_recordId];
    }
    
    function getEvidenceByAttackType(string memory _attackType) 
        public view returns (EvidenceRecord[] memory) 
    {
        uint256[] storage indices = attackTypeRecords[_attackType];
        EvidenceRecord[] memory result = new EvidenceRecord[](indices.length);
        for(uint i = 0; i < indices.length; i++) {
            result[i] = records[indices[i]];
        }
        return result;
    }
    
    function getTotalRecords() public view returns (uint256) {
        return totalRecords;
    }
}