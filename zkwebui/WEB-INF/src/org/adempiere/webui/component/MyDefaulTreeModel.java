/******************************************************************************
 * Copyright (C) 2008 Low Heng Sin                                            *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 *****************************************************************************/
package org.adempiere.webui.component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;

import javax.swing.tree.TreeNode;

import org.compiere.model.MTree;
import org.compiere.model.MTreeNode;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.Util;
import org.zkoss.lang.Objects;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zul.DefaultTreeNode;
import org.zkoss.zul.Tree;
import org.zkoss.zul.Treecell;
import org.zkoss.zul.Treecol;
import org.zkoss.zul.Treecols;
import org.zkoss.zul.Treeitem;
import org.zkoss.zul.TreeitemRenderer;
import org.zkoss.zul.Treerow;
import org.zkoss.zul.event.TreeDataEvent;

/**
 * 
 * @author Low Heng Sin
 * @author Yamel Senih, ysenih@erpcya.com, ERPCyA http://www.erpcya.com 2015-09-09
 *  	<li>FR [ 9223372036854775807 ] Add Support to Dynamic Tree
 * @see https://adempiere.atlassian.net/browse/ADEMPIERE-442
 *
 */
public class MyDefaulTreeModel extends org.zkoss.zul.DefaultTreeModel<Object>  implements  TreeitemRenderer<Object>, EventListener<Event>{

	/**
	 * 
	 */
	private static final long serialVersionUID = -4649471521757131755L;

	private static final CLogger logger = CLogger.getCLogger(MyDefaulTreeModel.class);
	
	private boolean itemDraggable;
	private List<EventListener<Event>> onDropListners = new ArrayList<EventListener<Event>>();

	public MyDefaulTreeModel(DefaultTreeNode<Object> root) {
		super(root);
	}
	
	/**
	 * Init Tree with where clause
	 * @param tree
	 * @param treeId
	 * @param windowNo
	 * @param whereClause
	 * @return
	 */
	public static MyDefaulTreeModel initADTree(Tree tree, int treeId, int windowNo, String whereClause) {
		return initADTree(tree, treeId, windowNo, true, whereClause, null);
	}
	
	/**
	 * @param tree
	 * @param AD_Tree_ID
	 * @param windowNo
	 * @param editable
	 * @param whereClause
	 * @param trxName
	 * @return SimpleTreeModel
	 */
	public static MyDefaulTreeModel initADTree(Tree tree, int AD_Tree_ID, int windowNo, boolean editable, String whereClause, String trxName) { 
		//	Change to where clause
		if(!Util.isEmpty(whereClause)) {
			whereClause = Env.parseContext(Env.getCtx(), windowNo, whereClause, false, false);
		}
		MTree vTree = new MTree (Env.getCtx(), AD_Tree_ID, editable, true, whereClause, trxName);
		MTreeNode root = vTree.getRoot();
		MyDefaulTreeModel treeModel = MyDefaulTreeModel.createFrom(root);
		treeModel.setItemDraggable(editable);
		if(editable) {
			treeModel.addOnDropEventListener(new ADTreeOnDropListener(tree, treeModel, vTree, windowNo));
		}
		if (tree.getTreecols() == null) {
			Treecols treeCols = new Treecols();
			tree.appendChild(treeCols);
			Treecol treeCol = new Treecol();
			treeCols.appendChild(treeCol);
		}
		tree.setPageSize(-1);
		try {
			tree.setTreeitemRenderer(treeModel);
			tree.setModel(treeModel);
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Failed to setup tree");
		}
		return treeModel;
	}
	
	/**
	 * 
	 * @param root
	 * @return SimpleTreeModel
	 */
	public static MyDefaulTreeModel createFrom(MTreeNode root) {
		MyDefaulTreeModel model = null;
		Enumeration<TreeNode> nodeEnum = root.children();
		DefaultTreeNode<Object> stRoot = new DefaultTreeNode<Object>(root, new ArrayList());
        while(nodeEnum.hasMoreElements()) {
        	MTreeNode childNode = (MTreeNode)nodeEnum.nextElement();
        	DefaultTreeNode<Object>  stNode = new DefaultTreeNode<Object> (childNode, new ArrayList());
        	stRoot.getChildren().add(stNode);
        	if (childNode.getChildCount() > 0) {
        		populate(stNode, childNode);
        	}
        }
        model = new MyDefaulTreeModel(stRoot);
		return model;
	}

	private static void populate(DefaultTreeNode<Object> stNode, MTreeNode root) {
		Enumeration<TreeNode> nodeEnum = root.children();
		while(nodeEnum.hasMoreElements()) {
			MTreeNode childNode = (MTreeNode)nodeEnum.nextElement();
			DefaultTreeNode<Object> stChildNode = new DefaultTreeNode<Object>(childNode, new ArrayList());
			stNode.getChildren().add(stChildNode);
			if (childNode.getChildCount() > 0) {
				populate(stChildNode, childNode);
			}
		}
	}

	/**
	 * @param ti
	 * @param node
	 */
	public void render(Treeitem ti, Object node,int index) {
		Treecell tc = new Treecell(Objects.toString(node));
		Treerow tr = null;
		if(ti.getTreerow()==null){
			tr = new Treerow();			
			tr.setParent(ti);
			if (isItemDraggable()) {
				tr.setDraggable("true");
			}
			if (!onDropListners.isEmpty()) {
				tr.setDroppable("true");
				tr.addEventListener(Events.ON_DROP, this);
			}
			// Color
			Object data = ((DefaultTreeNode<?>) node).getData();
				if (data instanceof MTreeNode) {
						final MTreeNode mNode = (MTreeNode) data;
						Color color = mNode.getColor();
						if (color != null){				
								String hex = ZkCssHelper.createHexColorString(color);
								ZkCssHelper.appendStyle(tc, "color: #" + hex);
							}				
						ti.setTooltiptext(mNode.getDescription());
						if (mNode.isSummary())
					    	ZkCssHelper.appendStyle(tc, "font-weight: bold");
						}
		}else{
			tr = ti.getTreerow(); 
			tr.getChildren().clear();
		}				
		tc.setParent(tr);
		
		ti.setValue(node);
	}

	/**
	 * Add to root
	 * @param newNode
	 */
	public void addNode(DefaultTreeNode<Object>  newNode) {
		DefaultTreeNode<Object>  root = getRoot();
		root.getChildren().add(newNode);
		fireEvent(root, root.getChildCount() - 1, root.getChildCount() - 1, TreeDataEvent.INTERVAL_ADDED);
	}

	@Override
	public DefaultTreeNode<Object> getRoot() {
		return (DefaultTreeNode<Object>) super.getRoot();
	}

	/**
	 * @param treeNode
	 */
	public void removeNode(DefaultTreeNode<Object> treeNode) {
		int path[] = this.getPath(treeNode);
		
		if (path != null && path.length > 0) {
			DefaultTreeNode<Object> parentNode = getRoot();
			int index = path.length - 1;
			for (int i = 0; i < index; i++) {
				parentNode = (DefaultTreeNode<Object> ) getChild(parentNode, path[i]);
			}
			
			
			parentNode.getChildren().remove(path[index]);
			fireEvent(parentNode, path[index], path[index], TreeDataEvent.INTERVAL_REMOVED);
		}
	}
	
	/**
	 * @param b
	 */
	public void setItemDraggable(boolean b) {
		itemDraggable = b;
	}
	
	/**
	 * @return boolean
	 */
	public boolean isItemDraggable() {
		return itemDraggable;
	}
	
	/**
	 * @param listener
	 */
	public void addOnDropEventListener(EventListener<Event> listener) {
		onDropListners.add(listener);
	}

	/**
	 * @param event
	 * @see EventListener#onEvent(Event)
	 */
	public void onEvent(Event event) throws Exception {
		if (Events.ON_DROP.equals(event.getName())) {
			for (EventListener<Event> listener : onDropListners) {
				listener.onEvent(event);
			}
		}
	}

	/**
	 * @param treeNode
	 * @return SimpleTreeNode
	 */
	public DefaultTreeNode<Object> getParent(DefaultTreeNode<Object> treeNode) {
		int path[] = this.getPath(treeNode);
		
		if (path != null && path.length > 0) {
			DefaultTreeNode<Object> parentNode = getRoot();
			int index = path.length - 1;
			for (int i = 0; i < index; i++) {
				parentNode = (DefaultTreeNode<Object>) getChild((org.zkoss.zul.TreeNode<Object>)parentNode, path[i]);
			}
						
			return parentNode;
		}
		
		return null;
	}
	/**
	 * @param newParent
	 * @param newNode
	 * @param index
	 */
	public void addNode(DefaultTreeNode<Object> newParent, DefaultTreeNode<Object> newNode, int index) {
		newParent.getChildren().add(index, newNode);
		fireEvent(newParent, index, index, TreeDataEvent.INTERVAL_ADDED);
	}
	
	
	/**
	 * @param fromNode
	 * @param recordId
	 * @return SimpleTreeNode
	 */
	public DefaultTreeNode<Object> find(DefaultTreeNode<Object> fromNode, int recordId) {
		if (fromNode == null)
			fromNode = getRoot();
		MTreeNode data = (MTreeNode) fromNode.getData();
		if (data.getNode_ID() == recordId) 
			return fromNode;

		// If the MTree model and the tree model aren't in sync, the data 
		// could include a new node that hasn't been initialized (node_id == -1).  
		// This will have no children causing a NPE error in isLeaf().
		try {
			if (isLeaf(fromNode)) 
				return null;
		} catch (NullPointerException e) {
			logger.severe("Uninitialized node exists in tree. Node ID: " + data.getNode_ID());
			return null;
		}
				
		int cnt = getChildCount(fromNode);
		for(int i = 0; i < cnt; i++ ) {
			DefaultTreeNode<Object> child = (DefaultTreeNode<Object>) getChild(fromNode, i);
			DefaultTreeNode<Object>treeNode = find(child, recordId);
			if (treeNode != null)
				return treeNode;
		}
		return null;
	}
	
	/**
	 * @param node
	 */
	public void nodeUpdated(DefaultTreeNode<Object> node) {
		DefaultTreeNode<Object> parent = getParent(node);
        if (parent != null) {
            int i = parent.getChildren().indexOf(node);
            fireEvent(parent, i, i, TreeDataEvent.CONTENTS_CHANGED);
        }
	}

}
